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
 * The reservation of {@link LockingReservationService} plus the member limit made safe.
 *
 * <p>The limit is a rule over a set of rows (the member's loans and reservations). Two requests of
 * the same member for different copies never touch a common row, so locking the copies cannot
 * protect it: both count the same total and both insert (write skew). Locking the member row first
 * gives them one row in common: the second request waits until the first commits, then counts
 * the first one's reservation too. The member row always exists, so even a member's first request
 * has something to lock.
 *
 * <p>Locks are always taken in the same order: member, then copies.
 */
@Service
public class MemberLockingReservationService {

    private final MemberRepository members;
    private final CopyRepository copies;
    private final ReservationRepository reservations;
    private final EntityManager entityManager;
    private final RaceWindow raceWindow;
    private final Clock clock;

    public MemberLockingReservationService(MemberRepository members, CopyRepository copies,
                                           ReservationRepository reservations, EntityManager entityManager,
                                           RaceWindow raceWindow, Clock clock) {
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

        // Before any lock: a thread holding a lock must never wait for the others here.
        raceWindow.pause();

        // LOCK the member: the other requests of this member wait here until this one ends.
        Member member = members.lockById(memberId).orElseThrow(() -> new NotFoundException("Member", memberId));
        // COUNT while holding it: no other request of this member can add items meanwhile.
        MemberLimit.check(reservations.countActiveItems(memberId, now), copyIds.size());

        List<Copy> locked = copies.lockCopies(copyIds);
        Reservation.checkAvailable(copyIds, locked, now);

        Reservation reservation = new Reservation(member, copyIds, now);
        entityManager.persist(reservation);
        reservation.reserve(locked, now);
        return ReservationResponse.from(reservation);
    }
}
