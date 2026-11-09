package cat.tecnocampus.library.expiry;

import cat.tecnocampus.library.MovableClock;
import cat.tecnocampus.library.domain.Copy;
import cat.tecnocampus.library.domain.CopyStatus;
import cat.tecnocampus.library.domain.ReservationStatus;
import cat.tecnocampus.library.expiry.ExpiryDemo.Facts;
import cat.tecnocampus.library.loan.LoanResponse;
import cat.tecnocampus.library.persistence.CopyRepository;
import cat.tecnocampus.library.persistence.LoanRepository;
import cat.tecnocampus.library.persistence.ReservationRepository;
import cat.tecnocampus.library.reservation.ReservationResponse;
import cat.tecnocampus.library.scenario.TransitionPauses;
import cat.tecnocampus.library.scenario.TransitionPauses.Stop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The forced interleavings of the expiry lesson, one fresh fixture per round. Each method takes
 * the operations as functions, so the same round runs on the naive and on the better versions.
 * The protocol follows the reference: real threads, each committing its own transaction; pause
 * points decide who commits first; the facts are read after both have committed.
 */
@Component
public class ExpiryRaces {

    /** A collection (S2): Anna (member01) collects copy N0001. */
    public interface Collect extends BiFunction<UUID, UUID, CollectionResponse> {
    }

    private static final Logger log = LoggerFactory.getLogger(ExpiryRaces.class);

    private final ExpiryDemo demo;
    private final MovableClock clock;
    private final TransitionPauses pauses;
    private final ReservationRepository reservations;
    private final CopyRepository copies;
    private final LoanRepository loans;

    public ExpiryRaces(ExpiryDemo demo, MovableClock clock, TransitionPauses pauses,
                       ReservationRepository reservations, CopyRepository copies, LoanRepository loans) {
        this.demo = demo;
        this.clock = clock;
        this.pauses = pauses;
        this.reservations = reservations;
        this.copies = copies;
        this.loans = loans;
    }

    // ---- S2: collect at the deadline while the sweep expires --------------------------------------

    public record Round(String collect, String expire, Facts facts) {

        @Override
        public String toString() {
            return "collect=%s expire=%s | %s".formatted(collect, expire, facts);
        }
    }

    /**
     * Collection validates one microsecond before the deadline and stops; the clock reaches the
     * deadline; expiry reads the reservation (still ACTIVE: nothing committed) and stops; collection
     * commits; expiry continues.
     */
    public Round collectionCommitsFirst(Collect collect, ReservationExpirer expirer) {
        demo.start();
        try {
            UUID anna = demo.member(1);
            ReservationResponse reservation = demo.reserve(1, "N0001");
            clock.fixAt(reservation.expiresAt().minus(1, ChronoUnit.MICROS));

            Stop collecting = pauses.stopAt(TransitionPauses.COLLECT_VALIDATED, reservation.id());
            Future<CollectionResponse> collection = demo.async(() -> collect.apply(anna, reservation.id()));
            collecting.awaitReachedOrDone(collection);

            clock.fixAt(reservation.expiresAt());
            Stop expiring = pauses.stopAt(TransitionPauses.EXPIRY_READ, reservation.id());
            Future<Boolean> expiry = demo.async(() -> expirer.expire(reservation.id()));
            expiring.awaitReachedOrDone(expiry);

            collecting.resume();
            String collected = ExpiryDemo.outcome(collection);
            expiring.resume();
            String expired = ExpiryDemo.outcome(expiry);
            return log("collection first", new Round(collected, expired,
                    demo.facts(reservation.id(), anna, "N0001")));
        } finally {
            demo.finish();
        }
    }

    /**
     * Collection reads the clock and the reservation one microsecond before the deadline and
     * stops; the clock reaches the deadline; expiry runs and commits; collection continues.
     */
    public Round expiryCommitsFirst(Collect collect, ReservationExpirer expirer) {
        demo.start();
        try {
            UUID anna = demo.member(1);
            ReservationResponse reservation = demo.reserve(1, "N0001");
            clock.fixAt(reservation.expiresAt().minus(1, ChronoUnit.MICROS));

            Stop collecting = pauses.stopAt(TransitionPauses.COLLECT_READ, reservation.id());
            Future<CollectionResponse> collection = demo.async(() -> collect.apply(anna, reservation.id()));
            collecting.awaitReachedOrDone(collection);

            clock.fixAt(reservation.expiresAt());
            String expired = ExpiryDemo.outcome(demo.async(() -> expirer.expire(reservation.id())));
            collecting.resume();
            String collected = ExpiryDemo.outcome(collection);
            return log("expiry first", new Round(collected, expired, demo.facts(reservation.id(), anna, "N0001")));
        } finally {
            demo.finish();
        }
    }

    // ---- S3: a stale cancellation ---------------------------------------------------------------

    public record CancelRound(String cancel, String collect, Facts facts) {

        @Override
        public String toString() {
            return "cancel=%s collect=%s | %s".formatted(cancel, collect, facts);
        }
    }

    /** Cancellation reads the reservation and stops; Anna collects at the desk; cancellation continues. */
    public CancelRound staleCancel(BiConsumer<UUID, UUID> cancel, Collect collect) {
        demo.start();
        try {
            UUID anna = demo.member(1);
            ReservationResponse reservation = demo.reserve(1, "N0001");
            clock.advance(Duration.ofHours(1));

            Stop cancelling = pauses.stopAt(TransitionPauses.CANCEL_READ, reservation.id());
            Future<String> cancellation = demo.async(() -> {
                cancel.accept(anna, reservation.id());
                return "204 cancelled";
            });
            cancelling.awaitReachedOrDone(cancellation);
            String collected = ExpiryDemo.outcome(demo.async(() -> collect.apply(anna, reservation.id())));
            cancelling.resume();
            String cancelled = ExpiryDemo.outcome(cancellation);
            return log("stale cancel", new CancelRound(cancelled, collected,
                    demo.facts(reservation.id(), anna, "N0001")));
        } finally {
            demo.finish();
        }
    }

    // ---- S4: the late sweep after a lazy re-reservation -------------------------------------------

    public record LateSweep(ExpirySweep.SweepResult sweep, ReservationStatus anna, ReservationStatus bruno,
                            CopyStatus copy, String copyHeldBy, String claraReserves, long liveReservationsOfTheCopy) {
    }

    /**
     * Sequential, no pause points. Anna's reservation of N0002 expires; five minutes later Bruno
     * reserves N0002 (an expired reservation no longer holds it); then the sweep reaches Anna's
     * reservation; then Clara asks for N0002.
     */
    public LateSweep lateSweep(ReservationExpirer expirer) {
        demo.start();
        try {
            ReservationResponse anna = demo.reserve(1, "N0002");
            clock.fixAt(anna.expiresAt().plus(Duration.ofMinutes(5)));
            ReservationResponse bruno = demo.reserve(2, "N0002");

            ExpirySweep.SweepResult sweep = new ExpirySweep(reservations, expirer, clock).sweepOnce();
            Future<ReservationResponse> claraReserving = demo.async(() -> demo.reserve(3, "N0002"));
            String clara = ExpiryDemo.outcome(claraReserving);

            Copy copy = copies.findById(demo.copyId("N0002")).orElseThrow();
            String holder = copy.getReservationId() == null ? "none"
                    : copy.getReservationId().equals(bruno.id()) ? "Bruno" : "someone else";
            List<UUID> promised = new java.util.ArrayList<>(List.of(anna.id(), bruno.id()));
            if (clara.startsWith("201")) {
                promised.add(claraReserving.resultNow().id());
            }
            long live = reservations.findAllById(promised).stream().filter(r -> r.isLiveAt(clock.instant())).count();
            return log("late sweep", new LateSweep(sweep, status(anna), status(bruno), copy.getStatus(), holder,
                    clara, live));
        } finally {
            demo.finish();
        }
    }

    // ---- S5: expiry and a new reservation of the same member, locks in opposite orders -----------

    public record DeadlockRound(String reserve, String expire, ReservationStatus oldReservation, CopyStatus copy,
                                String copyHeldBy) {
    }

    /**
     * Anna's old reservation of N0003 has just expired. Anna reserves N0003 and N0004 again (member
     * lock first) while the sweep expires the old one. Each stops holding its first lock, then both
     * continue and ask for the lock the other one holds.
     */
    public DeadlockRound deadlock(ReservationExpirer expirer) {
        demo.start();
        try {
            UUID anna = demo.member(1);
            ReservationResponse old = demo.reserve(1, "N0003");
            clock.fixAt(old.expiresAt());

            Stop reservingStop = pauses.stopAt(TransitionPauses.RESERVE_MEMBER_LOCKED, anna);
            Stop expiringStop = pauses.stopAt(TransitionPauses.EXPIRY_COPIES_LOCKED, old.id());
            Future<ReservationResponse> reserving = demo.async(() -> demo.reserve(1, "N0003", "N0004"));
            reservingStop.awaitReachedOrDone(reserving);
            Future<Boolean> expiring = demo.async(() -> expirer.expire(old.id()));
            expiringStop.awaitReachedOrDone(expiring);

            reservingStop.resume();
            expiringStop.resume();
            String reserved = ExpiryDemo.outcome(reserving);
            String expired = ExpiryDemo.outcome(expiring);

            Copy copy = copies.findById(demo.copyId("N0003")).orElseThrow();
            String holder = copy.getReservationId() == null ? "none"
                    : copy.getReservationId().equals(old.id()) ? "the old reservation" : "the new reservation";
            return log("deadlock", new DeadlockRound(reserved, expired, status(old), copy.getStatus(), holder));
        } finally {
            demo.finish();
        }
    }

    // ---- S8: the same return twice, and a reservation in between ----------------------------------

    public record ReturnRound(String firstReturn, String brunoReserves, String secondReturn, CopyStatus copy,
                              String copyHeldBy, ReservationStatus brunosReservation, long openLoans,
                              String claraReserves) {
    }

    /**
     * Anna collects N0005 and, days later, returns it. Both returns read the loan and the copy and
     * stop; the first commits; Bruno reserves N0005; the second continues; then Clara asks for N0005.
     */
    public ReturnRound doubleReturn(Function<UUID, LoanResponse> giveBack, Collect collect) {
        demo.start();
        try {
            UUID anna = demo.member(1);
            ReservationResponse reservation = demo.reserve(1, "N0005");
            UUID loan = collect.apply(anna, reservation.id()).loanIds().getFirst();
            clock.advance(Duration.ofDays(3));

            Stop firstStop = pauses.stopAt(TransitionPauses.RETURN_READ, loan);
            Future<LoanResponse> first = demo.async(() -> giveBack.apply(loan));
            firstStop.awaitReachedOrDone(first);
            Stop secondStop = pauses.stopAt(TransitionPauses.RETURN_READ, loan);
            Future<LoanResponse> second = demo.async(() -> giveBack.apply(loan));
            secondStop.awaitReachedOrDone(second);

            firstStop.resume();
            String firstOutcome = ExpiryDemo.outcome(first);
            Future<ReservationResponse> brunoReserving = demo.async(() -> demo.reserve(2, "N0005"));
            String bruno = ExpiryDemo.outcome(brunoReserving);
            secondStop.resume();
            String secondOutcome = ExpiryDemo.outcome(second);
            String clara = ExpiryDemo.outcome(() -> demo.reserve(3, "N0005"));

            Copy copy = copies.findById(demo.copyId("N0005")).orElseThrow();
            UUID brunosId = brunoReserving.isDone() && bruno.startsWith("201") ? brunoReserving.get().id() : null;
            String holder = copy.getReservationId() == null ? "none"
                    : copy.getReservationId().equals(brunosId) ? "Bruno" : "someone else";
            ReservationStatus brunos = brunosId == null ? null
                    : reservations.findById(brunosId).orElseThrow().getStatus();
            return log("double return", new ReturnRound(firstOutcome, bruno, secondOutcome, copy.getStatus(), holder,
                    brunos, loans.countOpenByCopy(copy.getId()), clara));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            demo.finish();
        }
    }

    private ReservationStatus status(ReservationResponse reservation) {
        return reservations.findById(reservation.id()).orElseThrow().getStatus();
    }

    private static <T> T log(String what, T result) {
        log.info("[expiry {}] {}", what, result);
        return result;
    }

    /** For reports: how many rounds ended in each distinct way. */
    public static <T> java.util.Map<String, Long> tally(List<T> rounds) {
        return rounds.stream().collect(java.util.stream.Collectors.groupingBy(Object::toString,
                java.util.TreeMap::new, java.util.stream.Collectors.counting()));
    }
}
