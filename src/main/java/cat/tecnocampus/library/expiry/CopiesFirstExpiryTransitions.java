package cat.tecnocampus.library.expiry;

import cat.tecnocampus.library.domain.Copy;
import cat.tecnocampus.library.domain.Reservation;
import cat.tecnocampus.library.persistence.CopyRepository;
import cat.tecnocampus.library.persistence.MemberRepository;
import cat.tecnocampus.library.persistence.ReservationRepository;
import cat.tecnocampus.library.scenario.TransitionPauses;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Expiry written by a team that has learned to lock (scenario S5): the reservation is loaded for
 * the first time under its lock, the time is read after the locks, the copies are released only if
 * they still belong to it. Everything is right except the <b>order</b> of the locks.
 *
 * <p>From the sweep's point of view the natural order is "the reservation, its copies, then the
 * member" (the member is locked because the member's item count changes). But reserving
 * ({@code MemberLockingReservationService}) locks the member first and the copies second. When the
 * two meet on the same member and the same copy, each holds the lock the other one is waiting for:
 * PostgreSQL detects the cycle after {@code deadlock_timeout} (1 s) and aborts one of them with
 * {@code 40P01}. Nobody hangs, but one of the two fails for no business reason.
 */
@Service
public class CopiesFirstExpiryTransitions implements ReservationExpirer {

    private final ReservationRepository reservations;
    private final CopyRepository copies;
    private final MemberRepository members;
    private final ExpiryNotices notices;
    private final TransitionPauses pauses;
    private final Clock clock;

    public CopiesFirstExpiryTransitions(ReservationRepository reservations, CopyRepository copies,
                                        MemberRepository members, ExpiryNotices notices, TransitionPauses pauses,
                                        Clock clock) {
        this.reservations = reservations;
        this.copies = copies;
        this.members = members;
        this.notices = notices;
        this.pauses = pauses;
        this.clock = clock;
    }

    @Override
    @Transactional
    public boolean expire(UUID reservationId) {
        Reservation reservation = reservations.lockById(reservationId).orElse(null);   // lock 1: reservation
        if (reservation == null) {
            return false;
        }
        List<Copy> held = copies.lockCopies(reservation.getCopyIds());                 // lock 2: copies
        pauses.observe(TransitionPauses.EXPIRY_COPIES_LOCKED, reservationId);
        members.lockById(reservation.getMember().getId());                             // lock 3: member (!)
        if (!reservation.expire(clock.instant())) {
            return false;
        }
        held.forEach(copy -> copy.releaseFrom(reservationId));
        notices.send(new ExpiryNotice(reservationId, reservation.getMember().getEmail(),
                held.stream().map(Copy::getBarcode).sorted().toList()));
        return true;
    }
}
