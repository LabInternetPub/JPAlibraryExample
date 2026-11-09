package cat.tecnocampus.library.expiry;

import cat.tecnocampus.library.MovableClock;
import cat.tecnocampus.library.domain.Reservation;
import cat.tecnocampus.library.domain.ReservationStatus;
import cat.tecnocampus.library.persistence.ReservationRepository;
import cat.tecnocampus.library.reservation.LockingReservationService;
import cat.tecnocampus.library.scenario.Measurement;
import cat.tecnocampus.library.scenario.SqlCounts;
import cat.tecnocampus.library.scenario.TransitionPauses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The sweep scenarios: many reservations at once (Central copies, see {@link ExpiryFixtures}),
 * one or two sweeps, and the stored facts and notices afterwards.
 */
@Component
public class SweepRaces {

    private static final Logger log = LoggerFactory.getLogger(SweepRaces.class);

    private final ExpiryDemo demo;
    private final ExpiryFixtures fixtures;
    private final MovableClock clock;
    private final TransitionPauses pauses;
    private final ExpiryNotices notices;
    private final ReservationRepository reservations;
    private final LockingReservationService locking;
    private final Measurement measurement;

    public SweepRaces(ExpiryDemo demo, ExpiryFixtures fixtures, MovableClock clock, TransitionPauses pauses,
                      ExpiryNotices notices, ReservationRepository reservations, LockingReservationService locking,
                      Measurement measurement) {
        this.demo = demo;
        this.fixtures = fixtures;
        this.clock = clock;
        this.pauses = pauses;
        this.notices = notices;
        this.reservations = reservations;
        this.locking = locking;
        this.measurement = measurement;
    }

    /** What the database and the members ended up with, for a set of reservations. */
    public record Outcome(int reservations, long storedExpired, long storedActive, int notices,
                          long reservationsNotifiedTwice, long notifiedButNotExpired) {
    }

    private Outcome outcome(List<UUID> ids) {
        Map<UUID, ReservationStatus> byId = reservations.findAllById(ids).stream()
                .collect(Collectors.toMap(Reservation::getId, Reservation::getStatus));
        Map<ReservationStatus, Long> statuses = byId.values().stream()
                .collect(Collectors.groupingBy(status -> status, Collectors.counting()));
        return new Outcome(ids.size(),
                statuses.getOrDefault(ReservationStatus.EXPIRED, 0L),
                statuses.getOrDefault(ReservationStatus.ACTIVE, 0L),
                ids.stream().mapToInt(notices::sentFor).sum(),
                ids.stream().filter(id -> notices.sentFor(id) > 1).count(),
                ids.stream().filter(id -> notices.sentFor(id) > 0 && byId.get(id) != ReservationStatus.EXPIRED).count());
    }

    /** Expired reservations on Central, and the clock just after the last deadline. */
    private List<ExpiryFixtures.Reserved> expiredOnCentral(int count) {
        Instant epoch = demo.start();
        List<ExpiryFixtures.Reserved> reserved = fixtures.reservationsOnCentral(count, epoch);
        clock.fixAt(epoch.plus(Reservation.DURATION).plusSeconds(count));
        return reserved;
    }

    private List<UUID> expired(int count) {
        return expiredOnCentral(count).stream().map(ExpiryFixtures.Reserved::reservationId).toList();
    }

    // ---- S6: two application instances sweep at once ---------------------------------------------

    public record TwoWorkers(ExpirySweep.SweepResult first, ExpirySweep.SweepResult second, int expireReturnedTrue,
                             Outcome outcome) {
    }

    /**
     * Two sweeps start together on the same expired reservations. They meet after reading their
     * candidates (so both have the same list) and after reading each reservation (so both read it
     * before either writes): the interleaving two schedulers can produce, on every candidate.
     */
    public TwoWorkers twoWorkers(ReservationExpirer expirer, int count) {
        List<UUID> ids = expired(count);
        try {
            pauses.meetAt(TransitionPauses.SWEEP_CANDIDATES_READ, 2);
            pauses.meetAt(TransitionPauses.EXPIRY_READ, 2);
            Future<ExpirySweep.SweepResult> a = demo.async(() -> sweep(expirer, count).sweepOnce());
            Future<ExpirySweep.SweepResult> b = demo.async(() -> sweep(expirer, count).sweepOnce());
            ExpirySweep.SweepResult first = a.get();
            ExpirySweep.SweepResult second = b.get();
            return log("two workers", new TwoWorkers(first, second, first.expired() + second.expired(), outcome(ids)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            demo.finish();
        }
    }

    private ExpirySweep sweep(ReservationExpirer expirer, int pageSize) {
        return new ExpirySweep(reservations, expirer, clock, pageSize, pauses);
    }

    // ---- S6b: a notice sent, then the transaction rolls back ---------------------------------------

    public record NoticeRollback(ExpirySweep.SweepResult sweep, Outcome outcome) {
    }

    /** Three expired reservations; expiring the second one fails right after its notice was sent. */
    public NoticeRollback noticeThenRollback(ReservationExpirer expirer) {
        List<UUID> ids = expired(3);
        try {
            pauses.failAt(TransitionPauses.EXPIRY_NOTIFIED, ids.get(1));
            ExpirySweep.SweepResult result = sweep(expirer, ExpirySweep.PAGE_SIZE).sweepOnce();
            return log("notice then rollback", new NoticeRollback(result, outcome(ids)));
        } finally {
            demo.finish();
        }
    }

    // ---- S7: a backlog, swept in one transaction or in bounded runs --------------------------------

    public record Backlog(int reservations, String sweep, String time, SqlCounts sql, String heapPeak,
                          String brunoReserves, String brunoWaited, Outcome outcome, String afterAFailure,
                          Outcome outcomeAfterAFailure) {
    }

    /**
     * A backlog of {@code count} expired reservations, swept by {@code sweepAll} (which sweeps until
     * nothing is due) three times on fresh data: measured alone; with Bruno reserving, meanwhile,
     * the copy of the first expired reservation; and with one reservation in the middle failing.
     */
    public Backlog backlog(Supplier<?> sweepAll, int count) {
        try {
            // 1. Measured alone
            List<UUID> ids = expired(count);
            Measurement.SingleRun<?> run =
                    measurement.withoutSqlLog(() -> measurement.once(sweepAll));
            Outcome measured = outcome(ids);

            // 2. Bruno wants the copy of the first expired reservation while the sweep runs
            List<ExpiryFixtures.Reserved> backlog = expiredOnCentral(count);
            UUID firstCopy = backlog.getFirst().copyId();
            UUID second = backlog.get(1).reservationId();
            Future<?> sweeping = demo.async(() -> measurement.withoutSqlLog(sweepAll));
            while (notices.sentFor(second) == 0 && !sweeping.isDone()) {
                Thread.onSpinWait();
            }
            long start = System.nanoTime();
            String bruno = ExpiryDemo.outcome(() -> locking.reserve(demo.member(60), List.of(firstCopy)));
            String waited = String.format(Locale.ROOT, "%.0f ms", (System.nanoTime() - start) / 1_000_000.0);
            ExpiryDemo.outcome(sweeping);

            // 3. One failure in the middle
            ids = expired(count);
            pauses.failAt(TransitionPauses.EXPIRY_NOTIFIED, ids.get(count / 2));
            String failed = ExpiryDemo.outcome(sweepAll::get);
            Outcome afterFailure = outcome(ids);

            return log("backlog", new Backlog(count, run.value().toString(), run.time(), run.sql(),
                    run.heap().peakIncrease(), bruno, waited, measured, failed, afterFailure));
        } finally {
            demo.finish();
        }
    }

    /** Several bounded runs, one after the other, until a run finds nothing more to expire. */
    public record Drained(int runs, int candidates, int expired, int failed) {
    }

    /**
     * What "the next run picks it up" looks like: bounded runs until nothing is due (or a run
     * expires nothing, so a reservation that always fails cannot loop forever).
     */
    public static Drained untilDone(ExpirySweep sweep) {
        int runs = 0;
        int candidates = 0;
        int expired = 0;
        int failed = 0;
        ExpirySweep.SweepResult run;
        do {
            run = sweep.sweepOnce();
            runs++;
            candidates += run.candidates();
            expired += run.expired();
            failed += run.failed();
        } while (run.candidates() > 0 && run.expired() > 0);
        return new Drained(runs, candidates, expired, failed);
    }

    /** A bounded sweep with the application's pause points, for scenarios. */
    public ExpirySweep boundedSweep(ReservationExpirer expirer) {
        return sweep(expirer, ExpirySweep.PAGE_SIZE);
    }

    /** For reports: how many results ended in each distinct way. */
    public static Map<String, Long> tally(List<?> results) {
        return results.stream().collect(Collectors.groupingBy(Object::toString, TreeMap::new, Collectors.counting()));
    }

    private static <T> T log(String what, T result) {
        log.info("[expiry {}] {}", what, result);
        return result;
    }
}
