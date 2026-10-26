package cat.tecnocampus.library.reservation;

import cat.tecnocampus.library.domain.Copy;
import cat.tecnocampus.library.domain.Member;
import cat.tecnocampus.library.domain.MemberLimit;
import cat.tecnocampus.library.domain.NotFoundException;
import cat.tecnocampus.library.domain.Reservation;
import cat.tecnocampus.library.persistence.CopyRepository;
import cat.tecnocampus.library.persistence.MemberRepository;
import cat.tecnocampus.library.persistence.ReservationRepository;
import cat.tecnocampus.library.scenario.RaceWindow;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lock, then check: the copies are read with SELECT ... FOR UPDATE, so no other transaction can
 * change them until this one ends. Fifty requests for one copy now run one after the other on
 * that row: the first reserves it, the other 49 wait, then read it as reserved and are rejected.
 *
 * <p>What makes it correct, beyond the lock itself:
 * <ol>
 *   <li>lock, check and write happen in this one transaction (the lock lasts until commit);</li>
 *   <li>the copies checked are the ones the locking query returned: nothing reads them unlocked
 *       before, or the persistence context would hand back the old objects;</li>
 *   <li>a rejection throws, so the transaction rolls back and leaves no trace;</li>
 *   <li>a copy that was not found counts as unavailable;</li>
 *   <li>the query locks in id order, the same for every request, so overlapping requests cannot
 *       deadlock; and it waits at most the lock timeout.</li>
 * </ol>
 *
 * <p>The member limit is checked here without any lock: correct against one request at a time,
 * not against two simultaneous requests of the same member (the member-limit lesson).
 */
@Service
public class LockingReservationService {

    private final MemberRepository members;
    private final CopyRepository copies;
    private final ReservationRepository reservations;
    private final EntityManager entityManager;
    private final RaceWindow raceWindow;
    private final Clock clock;

    public LockingReservationService(MemberRepository members, CopyRepository copies, ReservationRepository reservations,
                                     EntityManager entityManager, RaceWindow raceWindow, Clock clock) {
        this.members = members;
        this.copies = copies;
        this.reservations = reservations;
        this.entityManager = entityManager;
        this.raceWindow = raceWindow;
        this.clock = clock;
    }

    @Transactional
    public ReservationResponse reserve(UUID memberId, List<UUID> copyIds) {
        Instant now = clock.instant();
        Member member = members.findById(memberId).orElseThrow(() -> new NotFoundException("Member", memberId));
        MemberLimit.check(reservations.countActiveItems(memberId, now), copyIds.size());

        // After the count and before the lock. Before the lock, because a thread that held the lock
        // and waited here for the others would wait for threads that are waiting for its lock. After
        // the count, because that is where the member limit breaks: two requests of the same member
        // both count, then both reserve (the member-limit lesson uses this service as its naive version).
        raceWindow.pause();

        // LOCK, then CHECK the locked rows: the other requests for these copies wait here.
        List<Copy> locked = copies.lockCopies(copyIds);
        Reservation.checkAvailable(copyIds, locked, now);   // throws -> rollback, locks released

        // ACT
        Reservation reservation = new Reservation(member, copyIds, now);
        entityManager.persist(reservation);
        reservation.reserve(locked, now);
        return ReservationResponse.from(reservation);
    }
}
