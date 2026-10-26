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
 * Check-then-act: read the copies, check in Java that they are available, then write. Perfect
 * for one member at a time, and the rule even lives in the domain.
 *
 * <p>Under concurrency the check guarantees nothing. A plain SELECT takes no lock, so fifty
 * requests can all read the copy as available before any of them writes. Each then writes
 * {@code UPDATE book_copy ... WHERE id = ?}: the second writer waits for the first one's row
 * lock, re-checks only {@code id = ?} and overwrites. Fifty promises, one copy row: a lost update.
 */
@Service
public class NaiveReservationService {

    private final MemberRepository members;
    private final CopyRepository copies;
    private final ReservationRepository reservations;
    private final EntityManager entityManager;
    private final RaceWindow raceWindow;
    private final Clock clock;

    public NaiveReservationService(MemberRepository members, CopyRepository copies, ReservationRepository reservations,
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

        // READ and CHECK, without any lock
        List<Copy> found = copies.findAllById(copyIds);
        Reservation.checkAvailable(copyIds, found, now);

        // Between the check and the write nothing protects the decision. In normal operation this
        // does nothing; the concurrency scenarios use it to let every request check before any writes.
        raceWindow.pause();

        // ACT: Copy.reserve checks again, but on the same stale objects, so it passes too.
        Reservation reservation = new Reservation(member, copyIds, now);
        entityManager.persist(reservation);
        reservation.reserve(found, now);
        return ReservationResponse.from(reservation);   // the copies' UPDATEs leave at commit
    }
}
