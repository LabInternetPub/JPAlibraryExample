package cat.tecnocampus.library.expiry;

import cat.tecnocampus.library.domain.CopiesUnavailableException;
import cat.tecnocampus.library.domain.Copy;
import cat.tecnocampus.library.domain.Loan;
import cat.tecnocampus.library.domain.Member;
import cat.tecnocampus.library.domain.NotFoundException;
import cat.tecnocampus.library.domain.Reservation;
import cat.tecnocampus.library.domain.ReservationStatus;
import cat.tecnocampus.library.persistence.CopyRepository;
import cat.tecnocampus.library.persistence.MemberRepository;
import cat.tecnocampus.library.persistence.ReservationKey;
import cat.tecnocampus.library.persistence.ReservationRepository;
import cat.tecnocampus.library.scenario.TransitionPauses;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Collect, expire and cancel, option A (the reference): one row is the referee, taken with a lock.
 * Every transition:
 * <ol>
 *   <li>reads only scalar keys before arbitration ({@link ReservationKey}), so no stale managed
 *       Reservation can survive the lock;</li>
 *   <li>takes its locks in the order every other operation uses: <b>member → reservation →
 *       copies</b> (reserving takes member → copies), so no two of them can deadlock;</li>
 *   <li>reads the clock <em>after</em> the locks, and loads the Reservation for the first time
 *       under its lock, so the domain decides on the current status and the current time;</li>
 *   <li>does every side effect in the same transaction, through domain methods on locked rows,
 *       and throws (so everything rolls back) if a copy is not where it should be.</li>
 * </ol>
 * The second transaction to arrive necessarily sees the first one's committed result, and the
 * domain rule refuses it.
 *
 * <p>Expiry never waits: it uses {@code SKIP LOCKED} on the member and the reservation. If a
 * member is collecting or reserving right now, the sweep skips that reservation; it stays ACTIVE
 * and expired in the database, and a later run sees it again. The notice is published as an
 * event and sent only after the commit.
 */
@Service
public class LockingExpiryTransitions implements ReservationExpirer {

    private final ReservationRepository reservations;
    private final MemberRepository members;
    private final CopyRepository copies;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;
    private final TransitionPauses pauses;
    private final Clock clock;

    public LockingExpiryTransitions(ReservationRepository reservations, MemberRepository members,
                                    CopyRepository copies, EntityManager entityManager,
                                    ApplicationEventPublisher events, TransitionPauses pauses, Clock clock) {
        this.reservations = reservations;
        this.members = members;
        this.copies = copies;
        this.entityManager = entityManager;
        this.events = events;
        this.pauses = pauses;
        this.clock = clock;
    }

    @Transactional
    public CollectionResponse collect(UUID memberId, UUID reservationId) {
        ownedBy(memberId, reservationId);                                     // scalars only
        pauses.observe(TransitionPauses.COLLECT_READ, reservationId);
        Member member = members.lockById(memberId).orElseThrow();             // lock 1: member
        Reservation reservation = reservations.lockById(reservationId)        // lock 2: reservation,
                .orElseThrow(() -> new NotFoundException("Reservation", reservationId));   // read fresh
        Instant now = clock.instant();                                        // after the locks
        reservation.collect(now);                                             // current status, current time
        pauses.observe(TransitionPauses.COLLECT_VALIDATED, reservationId);

        List<Copy> held = copies.lockCopies(reservation.getCopyIds());        // lock 3: copies, id order
        if (held.size() != reservation.getQuantity()) {
            throw new CopiesUnavailableException(reservation.getCopyIds());
        }
        List<Loan> loans = held.stream().map(copy -> {
            copy.collect(reservationId, now);                                 // throws if not held by it
            return Loan.forCollection(member, copy, now);
        }).toList();
        loans.forEach(entityManager::persist);
        return new CollectionResponse(reservationId, ReservationStatus.COLLECTED,
                loans.stream().map(Loan::getId).toList(), now.plus(Loan.DURATION));
    }

    @Override
    @Transactional
    public boolean expire(UUID reservationId) {
        ReservationKey key = reservations.transitionKey(reservationId).orElse(null);
        if (key == null) {
            return false;
        }
        pauses.observe(TransitionPauses.EXPIRY_READ, reservationId);
        // Never wait behind a member at the desk, nor behind another sweep: skip, a later run retries.
        Member member = members.lockIfFree(key.memberId()).orElse(null);    // lock 1: member
        if (member == null) {
            return false;
        }
        Reservation reservation = reservations.lockIfFree(reservationId).orElse(null);   // lock 2
        if (reservation == null || !reservation.expire(clock.instant())) {
            return false;                                                      // already decided by someone else
        }
        List<Copy> held = copies.lockCopies(reservation.getCopyIds());         // lock 3: copies
        held.forEach(copy -> copy.releaseFrom(reservationId));                 // only if still held by it
        events.publishEvent(new ExpiryNotice(reservationId, member.getEmail(),
                held.stream().map(Copy::getBarcode).sorted().toList()));       // sent after the commit
        pauses.observe(TransitionPauses.EXPIRY_NOTIFIED, reservationId);
        return true;
    }

    @Transactional
    public void cancel(UUID memberId, UUID reservationId) {
        ownedBy(memberId, reservationId);                                     // scalars only
        pauses.observe(TransitionPauses.CANCEL_READ, reservationId);
        members.lockById(memberId);                                           // lock 1: member
        Reservation reservation = reservations.lockById(reservationId)        // lock 2: read fresh
                .orElseThrow(() -> new NotFoundException("Reservation", reservationId));
        reservation.cancel(memberId, clock.instant());
        copies.lockCopies(reservation.getCopyIds())                           // lock 3: copies
                .forEach(copy -> copy.releaseFrom(reservationId));
    }

    private void ownedBy(UUID memberId, UUID reservationId) {
        reservations.transitionKey(reservationId)
                .filter(key -> key.memberId().equals(memberId))
                .orElseThrow(() -> new NotFoundException("Reservation", reservationId));
    }
}
