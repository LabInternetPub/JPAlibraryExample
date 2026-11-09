package cat.tecnocampus.library.expiry;

import cat.tecnocampus.library.domain.Copy;
import cat.tecnocampus.library.domain.Loan;
import cat.tecnocampus.library.domain.Member;
import cat.tecnocampus.library.domain.NotFoundException;
import cat.tecnocampus.library.domain.Reservation;
import cat.tecnocampus.library.domain.ReservationStatus;
import cat.tecnocampus.library.persistence.CopyRepository;
import cat.tecnocampus.library.persistence.ReservationRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Collect, expire and cancel a reservation: read it, let the domain decide, write. Every method
 * is transactional, the time rule lives in {@link Reservation}, and the sweep calls
 * {@link #expire(UUID)} through this bean's proxy, one transaction per reservation. Many teams
 * would stop here, and every sequential test passes.
 *
 * <p>Under concurrency it breaks, in three ways at once:
 * <ol>
 *   <li><b>No arbitration on the reservation row.</b> Plain reads, no lock: collection and expiry
 *       can both read {@code ACTIVE}. Hibernate's dirty-checking {@code UPDATE reservation SET
 *       status = ? ... WHERE id = ?} has no condition on the old status, so the second writer
 *       silently overwrites the first one's decision (a lost update).</li>
 *   <li><b>Time read too early.</b> {@code collect} reads the clock before reading the
 *       reservation; if it then waits, it decides with an old "now".</li>
 *   <li><b>An ignored row count.</b> {@code lendOwned} returning 0 means "this reservation holds no
 *       copy any more"; collection ignores it and creates loans anyway.</li>
 * </ol>
 * And {@link #cancel(UUID, UUID)} shows the trap that survives the obvious fix: it locks the
 * reservation, but decides on the object it had loaded before the lock.
 *
 * <p>Expiry and cancellation release the copies with {@link Copy#makeAvailable()}, whoever holds
 * them now, and the notice is sent inside the transaction, before it commits.
 */
@Service
public class NaiveExpiryTransitions implements ReservationExpirer {

    private final ReservationRepository reservations;
    private final CopyRepository copies;
    private final EntityManager entityManager;
    private final ExpiryNotices notices;
    private final Clock clock;

    public NaiveExpiryTransitions(ReservationRepository reservations, CopyRepository copies,
                                  EntityManager entityManager, ExpiryNotices notices, Clock clock) {
        this.reservations = reservations;
        this.copies = copies;
        this.entityManager = entityManager;
        this.notices = notices;
        this.clock = clock;
    }

    @Transactional
    public CollectionResponse collect(UUID memberId, UUID reservationId) {
        Instant now = clock.instant();                                       // (1) time read first
        Reservation reservation = reservations.findById(reservationId)      // (2) plain read, no lock
                .filter(r -> r.getMember().getId().equals(memberId))
                .orElseThrow(() -> new NotFoundException("Reservation", reservationId));
        reservation.collect(now);                                            // (3) decided in Java
        List<UUID> copyIds = reservation.getCopyIds();   // before the bulk update clears the context

        copies.lendOwned(reservationId);                                     // (4) count ignored

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
        Instant now = clock.instant();
        Reservation reservation = reservations.findById(reservationId).orElse(null);   // plain read
        if (reservation == null || !reservation.expire(now)) {                           // decided in Java
            return false;
        }
        List<Copy> held = copies.findAllById(reservation.getCopyIds());
        held.forEach(Copy::makeAvailable);                // whoever holds them now
        notices.send(notice(reservation, held));          // sent before the commit
        return true;
    }

    @Transactional
    public void cancel(UUID memberId, UUID reservationId) {
        Reservation reservation = reservations.findById(reservationId)      // the entity, loaded now
                .orElseThrow(() -> new NotFoundException("Reservation", reservationId));
        // "Add a lock": SELECT ... FOR UPDATE really waits for a concurrent collection, but it hands
        // back the object above, with the status it had before waiting.
        reservations.lockById(reservationId);
        reservation.cancel(memberId, clock.instant());
        copies.findAllById(reservation.getCopyIds()).forEach(Copy::makeAvailable);
    }

    private static ExpiryNotice notice(Reservation reservation, List<Copy> copies) {
        return new ExpiryNotice(reservation.getId(), reservation.getMember().getEmail(),
                copies.stream().map(Copy::getBarcode).sorted().toList());
    }
}
