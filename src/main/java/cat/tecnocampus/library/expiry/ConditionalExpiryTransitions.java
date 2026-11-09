package cat.tecnocampus.library.expiry;

import cat.tecnocampus.library.domain.CopiesUnavailableException;
import cat.tecnocampus.library.domain.Copy;
import cat.tecnocampus.library.domain.Loan;
import cat.tecnocampus.library.domain.Member;
import cat.tecnocampus.library.domain.NotFoundException;
import cat.tecnocampus.library.domain.ReservationNotActiveException;
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
 * Collect and expire, option B: the lesson-4 pattern. A conditional {@code UPDATE} on the
 * reservation is the arbitration point, and its affected-row count says who won: 1, do the side
 * effects in the same transaction and check their counts too; 0, someone else decided first (or
 * the time rule says no), do nothing.
 *
 * <p>It works because under READ COMMITTED PostgreSQL makes a second UPDATE of the same row wait
 * for the first one, then re-evaluates the WHERE clause on the committed row.
 *
 * <p>Costs compared with option A: the time rule is written twice (in {@code Reservation} and in
 * the queries), with the same boundary; and the decision leaves the domain object. The member is
 * still locked first, to keep the lock order of every other operation.
 */
@Service
public class ConditionalExpiryTransitions implements ReservationExpirer {

    private final ReservationRepository reservations;
    private final MemberRepository members;
    private final CopyRepository copies;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;
    private final TransitionPauses pauses;
    private final Clock clock;

    public ConditionalExpiryTransitions(ReservationRepository reservations, MemberRepository members,
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
        pauses.observe(TransitionPauses.COLLECT_READ, reservationId);
        members.lockById(memberId).orElseThrow(() -> new NotFoundException("Member", memberId));   // lock order
        Instant now = clock.instant();
        if (reservations.collectIfLive(reservationId, memberId, now) == 0) {   // the arbitration point
            throw reservations.transitionKey(reservationId).filter(key -> key.memberId().equals(memberId)).isPresent()
                    ? new ReservationNotActiveException(reservationId)
                    : new NotFoundException("Reservation", reservationId);
        }
        pauses.observe(TransitionPauses.COLLECT_VALIDATED, reservationId);

        List<UUID> copyIds = reservations.findCopyIds(reservationId);
        if (copies.lendOwned(reservationId) != copyIds.size()) {             // the count is checked
            throw new CopiesUnavailableException(copyIds);                   // rolls the collection back
        }
        Member member = entityManager.getReference(Member.class, memberId);
        List<Loan> loans = copyIds.stream()
                .map(id -> Loan.forCollection(member, entityManager.getReference(Copy.class, id), now))
                .toList();
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
        Member member = members.lockIfFree(key.memberId()).orElse(null);    // never wait behind a member
        if (member == null) {
            return false;
        }
        String email = member.getEmail();          // read before the bulk updates clear the context
        if (reservations.expireIfDue(reservationId, clock.instant()) == 0) {
            return false;                                                     // someone else decided first
        }
        List<String> barcodes = copies.findBarcodesOfReservation(reservationId);
        copies.releaseOwned(reservationId);        // 0 is fine: a newer reservation may hold the copy
        events.publishEvent(new ExpiryNotice(reservationId, email, barcodes));
        pauses.observe(TransitionPauses.EXPIRY_NOTIFIED, reservationId);
        return true;
    }
}
