package cat.tecnocampus.library.expiry;

import cat.tecnocampus.library.MovableClock;
import cat.tecnocampus.library.domain.CopiesUnavailableException;
import cat.tecnocampus.library.domain.Copy;
import cat.tecnocampus.library.domain.CopyStatus;
import cat.tecnocampus.library.domain.MemberLimitExceededException;
import cat.tecnocampus.library.domain.NotFoundException;
import cat.tecnocampus.library.domain.ReservationExpiredException;
import cat.tecnocampus.library.domain.ReservationNotActiveException;
import cat.tecnocampus.library.domain.ReservationStatus;
import cat.tecnocampus.library.loan.LoanResponse;
import cat.tecnocampus.library.persistence.CopyRepository;
import cat.tecnocampus.library.persistence.LoanRepository;
import cat.tecnocampus.library.persistence.ReservationRepository;
import cat.tecnocampus.library.reservation.MemberLockingReservationService;
import cat.tecnocampus.library.reservation.ReservationLookupService;
import cat.tecnocampus.library.reservation.ReservationResponse;
import cat.tecnocampus.library.scenario.DemoReset;
import cat.tecnocampus.library.scenario.TransitionPauses;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * What every expiry scenario and race test shares: a known starting point, the members and copies
 * by business key, threads that commit on their own, and the facts of the invariant read back
 * after everything has committed.
 *
 * <p>The clock starts 30 days in the past ({@link #start()}). Central's example reservations have
 * deadlines around the application's start, so a scenario's sweep never reaches them, and the
 * shelf-map lesson keeps its expired-but-stored-as-reserved copies.
 */
@Component
public class ExpiryDemo {

    private final DemoReset reset;
    private final MovableClock clock;
    private final TransitionPauses pauses;
    private final ExpiryNotices notices;
    private final ReservationLookupService lookup;
    private final MemberLockingReservationService reserving;
    private final ReservationRepository reservations;
    private final CopyRepository copies;
    private final LoanRepository loans;
    private final ExecutorService threads = Executors.newCachedThreadPool(
            Thread.ofPlatform().daemon().name("expiry-demo-", 0).factory());

    public ExpiryDemo(DemoReset reset, MovableClock clock, TransitionPauses pauses, ExpiryNotices notices,
                      ReservationLookupService lookup, MemberLockingReservationService reserving,
                      ReservationRepository reservations, CopyRepository copies, LoanRepository loans) {
        this.reset = reset;
        this.clock = clock;
        this.pauses = pauses;
        this.notices = notices;
        this.lookup = lookup;
        this.reserving = reserving;
        this.reservations = reservations;
        this.copies = copies;
        this.loans = loans;
    }

    /** Fresh data, no notices, nothing armed, the clock stopped 30 days ago. Returns that instant. */
    public Instant start() {
        pauses.disarm();
        reset.resetNorthAndScenarioMembers();
        notices.clear();
        clock.reset();
        Instant epoch = clock.instant().minus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        clock.fixAt(epoch);
        return epoch;
    }

    /** Releases any stopped thread and gives the application its real clock back. */
    public void finish() {
        pauses.disarm();
        clock.reset();
    }

    public UUID member(int n) {
        return lookup.memberId("member%02d@library.test".formatted(n));
    }

    public UUID copyId(String barcode) {
        return lookup.copyIds("North", List.of(barcode)).getFirst();
    }

    /** Reserves North copies for scenario member {@code n}, at the clock's current instant. */
    public ReservationResponse reserve(int member, String... barcodes) {
        return reserving.reserve(member(member), lookup.copyIds("North", List.of(barcodes)));
    }

    /** {@code GET /reservations/{id}} as the member sees it: 410 once the deadline has passed. */
    public String read(UUID reservationId) {
        return outcome(() -> lookup.get(reservationId).status().name());
    }

    /** What the shelf map shows for a North copy now, and what its row stores. */
    public String shelf(String barcode) {
        Copy copy = copies.findById(copyId(barcode)).orElseThrow();
        return "shows " + copy.visibleStatusAt(clock.instant()) + ", stored " + copy.getStatus();
    }

    public long activeItems(int member) {
        return reservations.countActiveItems(member(member), clock.instant());
    }

    public <T> Future<T> async(Callable<T> task) {
        return threads.submit(task);
    }

    /** What the call answered, as the client would see it, or what it threw. Waits for it. */
    public static String outcome(Future<?> task) {
        try {
            return describe(task.get(30, TimeUnit.SECONDS));
        } catch (ExecutionException e) {
            return describe(e.getCause());
        } catch (TimeoutException e) {
            return "still running after 30 s";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    public static String outcome(Callable<?> call) {
        try {
            return describe(call.call());
        } catch (Exception e) {
            return describe(e);
        }
    }

    private static String describe(Object value) {
        return switch (value) {
            case null -> "200";
            case Boolean b -> b.toString();
            case CollectionResponse c -> "200 collected";
            case LoanResponse l -> "200 returned";
            case ReservationResponse r -> "201 reserved";
            default -> value.toString();
        };
    }

    private static String describe(Throwable error) {
        return switch (error) {
            case ReservationNotActiveException e -> "409 not active";
            case CopiesUnavailableException e -> "409 copies unavailable";
            case MemberLimitExceededException e -> "409 member limit";
            case NotFoundException e -> "404 not found";
            case ReservationExpiredException e -> "410 gone";
            case TransitionPauses.InjectedFailure e -> "injected failure";
            default -> error.getClass().getSimpleName() + sqlState(error);
        };
    }

    /** The SQL state of the underlying database error, e.g. 40P01 for a deadlock. */
    private static String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return " (SQL state " + sql.getSQLState() + ")";
            }
        }
        return "";
    }

    /**
     * The facts of the invariant for a one-copy reservation, read after everything committed:
     * reservation status, the copy and who holds it, open loans of the copy, the member's active
     * items, notices sent.
     */
    public Facts facts(UUID reservationId, UUID memberId, String barcode) {
        Copy copy = copies.findById(copyId(barcode)).orElseThrow();
        String holder = copy.getReservationId() == null ? "none"
                : copy.getReservationId().equals(reservationId) ? "this reservation" : "another reservation";
        return new Facts(reservations.findById(reservationId).orElseThrow().getStatus(), copy.getStatus(), holder,
                loans.countOpenByCopy(copy.getId()), reservations.countActiveItems(memberId, clock.instant()),
                notices.sentFor(reservationId));
    }

    public record Facts(ReservationStatus reservation, CopyStatus copy, String copyHeldBy, long openLoans,
                        long activeItems, int notices) {

        public Facts {
            Objects.requireNonNull(reservation);
        }

        @Override
        public String toString() {
            return "reservation=%s copy=%s (held by %s) openLoans=%d activeItems=%d notices=%d"
                    .formatted(reservation, copy, copyHeldBy, openLoans, activeItems, notices);
        }
    }
}
