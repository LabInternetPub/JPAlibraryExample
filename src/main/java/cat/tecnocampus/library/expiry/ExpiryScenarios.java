package cat.tecnocampus.library.expiry;

import cat.tecnocampus.library.loan.ConditionalLoanReturnService;
import cat.tecnocampus.library.loan.NaiveLoanReturnService;
import cat.tecnocampus.library.scenario.Scenario;
import cat.tecnocampus.library.scenario.ScenarioReport;
import cat.tecnocampus.library.scenario.SimpleScenario;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.stream.IntStream;

/**
 * The naive scenarios of the expiry lesson (S2–S8 of design/reservation-expiry-and-return.md).
 * Each one runs on fresh data with the clock set 30 days back, and reports the facts of the
 * invariant read after everything committed. Run them with the scheduled sweep off (the default).
 */
@Configuration
class ExpiryScenarios {

    static final String LESSON = "expiry";
    static final int ROUNDS = 10;
    static final int SHORT_ROUNDS = 5;
    static final int WORKERS_RESERVATIONS = 100;
    static final int BACKLOG = 1000;

    static final String REQUIRED_COLLECTION_FIRST =
            "collect=200 collected expire=false | reservation=COLLECTED copy=ON_LOAN (held by none) openLoans=1 activeItems=1 notices=0";
    static final String REQUIRED_EXPIRY_FIRST =
            "collect=409 not active expire=true | reservation=EXPIRED copy=AVAILABLE (held by none) openLoans=0 activeItems=0 notices=1";

    private final ExpiryRaces races;
    private final SweepRaces sweeps;

    ExpiryScenarios(ExpiryRaces races, SweepRaces sweeps) {
        this.races = races;
        this.sweeps = sweeps;
    }

    @Bean
    Scenario expiryBoundary(NaiveExpiryTransitions naive) {
        return new SimpleScenario("expiry-boundary", LESSON,
                "S1: one operation at a time, one microsecond before the deadline and exactly at it (naive)",
                scenario -> {
                    ScenarioReport.Builder report = ScenarioReport.of(scenario);
                    races.boundary(naive::collect, naive).forEach(report::result);
                    return report
                            .note("Every answer is right, on the naive version: one operation at a time, nothing "
                                    + "can go wrong. Sequential tests cannot see the race of collect-expiry-naive.")
                            .note("Lazy expiry on reads: at the deadline the shelf map, GET and the member's count "
                                    + "already treat the reservation as expired while its rows still say "
                                    + "ACTIVE / RESERVED. The sweep only stores what every reader already applies.")
                            .note("Collecting removed the deadline: a day later the copy is still ON_LOAN and the "
                                    + "collected reservation is no sweep candidate.")
                            .build();
                });
    }

    @Bean
    Scenario collectExpiryNaive(NaiveExpiryTransitions naive) {
        return new SimpleScenario("collect-expiry-naive", LESSON,
                "S2: Anna collects one microsecond before the deadline while the sweep expires her reservation",
                scenario -> {
                    var collectionFirst = rounds(ROUNDS, () -> races.collectionCommitsFirst(naive::collect, naive));
                    var expiryFirst = rounds(ROUNDS, () -> races.expiryCommitsFirst(naive::collect, naive));
                    return ScenarioReport.of(scenario)
                            .result("rounds per ordering", ROUNDS)
                            .result("required, collection commits first", REQUIRED_COLLECTION_FIRST)
                            .result("measured, collection commits first", ExpiryRaces.tally(collectionFirst))
                            .result("required, expiry commits first", REQUIRED_EXPIRY_FIRST)
                            .result("measured, expiry commits first", ExpiryRaces.tally(expiryFirst))
                            .note("Both operations answered success in both orderings: Anna was told \"collected\" "
                                    + "and \"expired\".")
                            .note("Collection first: the reservation says EXPIRED and the copy AVAILABLE, but Anna has "
                                    + "an open loan of it. The next member can reserve a copy that is in Anna's bag.")
                            .note("Expiry first: the reservation says COLLECTED, so a test that checks only the status "
                                    + "passes. Only the copy, the loans and the notices show the damage.")
                            .note("Why: plain reads, the decision in Java, and dirty checking's "
                                    + "UPDATE reservation SET status=? WHERE id=? with no condition on the old status "
                                    + "(see the SQL log). Collection also read the clock too early and ignored that "
                                    + "lendOwned updated 0 copies.")
                            .build();
                });
    }

    @Bean
    Scenario cancelStaleNaive(NaiveExpiryTransitions naive) {
        return new SimpleScenario("cancel-stale-naive", LESSON,
                "S3: Anna's late \"cancel\" read the reservation; she collects at the desk; the cancel goes on",
                scenario -> ScenarioReport.of(scenario)
                        .result("rounds", SHORT_ROUNDS)
                        .result("required", "cancel=409 not active collect=200 collected | reservation=COLLECTED "
                                + "copy=ON_LOAN (held by none) openLoans=1 activeItems=1 notices=0")
                        .result("measured", ExpiryRaces.tally(rounds(SHORT_ROUNDS,
                                () -> races.staleCancel(naive::cancel, naive::collect))))
                        .note("The cancel took a real lock (SELECT ... FOR UPDATE in the SQL log) and still decided "
                                + "on ACTIVE: the lock query returned the Reservation object already in the "
                                + "persistence context, with the fields it had before waiting.")
                        .note("Anna has the book on loan, and the system put it back on the shelf.")
                        .build());
    }

    @Bean
    Scenario sweepAfterRereserveNaive(NaiveExpiryTransitions naive) {
        return new SimpleScenario("sweep-after-rereserve-naive", LESSON,
                "S4: Anna's reservation expired, Bruno reserved the copy, then the sweep reached Anna's",
                scenario -> {
                    ExpiryRaces.LateSweep late = races.lateSweep(naive);
                    return ScenarioReport.of(scenario)
                            .result("sweep", late.sweep())
                            .result("Anna's reservation", late.anna())
                            .result("Bruno's reservation", late.bruno())
                            .result("copy N0002", late.copy() + ", held by " + late.copyHeldBy())
                            .result("Clara asks for N0002", late.claraReserves())
                            .result("live reservations promising N0002", late.liveReservationsOfTheCopy())
                            .note("No concurrency at all. Lazy expiry let Bruno take the copy before any sweep; the "
                                    + "naive sweep then made it AVAILABLE without checking who held it.")
                            .note("Bruno's reservation is ACTIVE but holds nothing, so Clara got the same copy: two "
                                    + "live promises for one copy.")
                            .build();
                });
    }

    @Bean
    Scenario sweepDeadlockNaive(CopiesFirstExpiryTransitions copiesFirst) {
        return new SimpleScenario("sweep-deadlock-naive", LESSON,
                "S5: the sweep locks reservation, copies, member; Anna's new reservation locks member, copies",
                scenario -> ScenarioReport.of(scenario)
                        .result("rounds", SHORT_ROUNDS)
                        .result("measured", ExpiryRaces.tally(rounds(SHORT_ROUNDS, () -> races.deadlock(copiesFirst))))
                        .note("Each holds the lock the other one waits for. PostgreSQL detects the cycle after "
                                + "deadlock_timeout (1 s) and aborts one of them with SQL state 40P01.")
                        .note("Nobody hangs, but a request with nothing wrong in it fails. If it is Anna's, she "
                                + "gets an error for a perfectly valid reservation.")
                        .build());
    }

    @Bean
    Scenario sweepTwoWorkersNaive(NaiveExpiryTransitions naive) {
        return new SimpleScenario("sweep-two-workers-naive", LESSON,
                "S6: two application instances sweep the same " + WORKERS_RESERVATIONS + " expired reservations",
                scenario -> {
                    SweepRaces.TwoWorkers workers = sweeps.twoWorkers(naive, WORKERS_RESERVATIONS);
                    return ScenarioReport.of(scenario)
                            .result("worker 1", workers.first())
                            .result("worker 2", workers.second())
                            .result("expire() returned true", workers.expireReturnedTrue())
                            .result("stored afterwards", workers.outcome())
                            .note("The stored rows look right: EXPIRED twice is still EXPIRED, AVAILABLE twice is "
                                    + "still AVAILABLE. Only the side effects show the bug: every member got two notices.")
                            .note("fixedDelay keeps one scheduler from overlapping with itself; two instances have "
                                    + "two schedulers. Only the database can arbitrate between them.")
                            .build();
                });
    }

    @Bean
    Scenario expiryNoticeRollbackNaive(NaiveExpiryTransitions naive) {
        return new SimpleScenario("expiry-notice-rollback-naive", LESSON,
                "S6b: three expired reservations; the second fails right after its notice was sent",
                scenario -> {
                    SweepRaces.NoticeRollback rollback = sweeps.noticeThenRollback(naive);
                    return ScenarioReport.of(scenario)
                            .result("sweep", rollback.sweep())
                            .result("stored afterwards", rollback.outcome())
                            .note("The notice is sent inside the transaction. The transaction rolled back, the "
                                    + "notice did not: a member was told that a reservation expired, and it is "
                                    + "still ACTIVE.")
                            .note("Something left the system that the database does not confirm: the library's "
                                    + "version of \"charged, but no ticket\".")
                            .build();
                });
    }

    @Bean
    Scenario sweepOneTransaction(OneTransactionSweep oneTransaction) {
        return new SimpleScenario("sweep-one-transaction", LESSON,
                "S7: a backlog of " + BACKLOG + " expired reservations, swept in one transaction",
                scenario -> {
                    SweepRaces.Backlog backlog = sweeps.backlog(oneTransaction::sweepAll, BACKLOG);
                    return ScenarioReport.of(scenario)
                            .result("expired reservations", backlog.reservations())
                            .result("sweep", backlog.sweep())
                            .result("time", backlog.time())
                            .sql("sweep", backlog.sql())
                            .result("heap peak increase", backlog.heapPeak())
                            .result("stored afterwards", backlog.outcome())
                            .result("Bruno reserves a copy the sweep released, during the sweep", backlog.brunoReserves())
                            .result("Bruno waited", backlog.brunoWaited())
                            .result("sweep with one failure in the middle", backlog.afterAFailure())
                            .result("stored after that sweep", backlog.outcomeAfterAFailure())
                            .note("Every expire() joined the sweep's transaction: all the reservations, copies and "
                                    + "members stayed in one persistence context, and each auto-flush dirty-checked "
                                    + "all of them.")
                            .note("A released copy stays locked until the final commit: Bruno waited for the whole "
                                    + "sweep.")
                            .note("One failure rolled back every expiry, while the notices already sent stayed sent.")
                            .build();
                });
    }

    @Bean
    Scenario returnDoubleScanNaive(NaiveLoanReturnService naiveReturn, NaiveExpiryTransitions naive) {
        return new SimpleScenario("return-double-scan-naive", LESSON,
                "S8: the return machine retries Anna's return; Bruno reserves the copy between the two",
                scenario -> ScenarioReport.of(scenario)
                        .result("rounds", SHORT_ROUNDS)
                        .result("measured", ExpiryRaces.tally(rounds(SHORT_ROUNDS,
                                () -> races.doubleReturn(naiveReturn::giveBack, naive::collect))))
                        .note("Both returns read the copy as ON_LOAN. The second one's UPDATE writes every column "
                                + "of its stale Copy (status, reservation_id, reserved_until), not only the ones "
                                + "that changed: Bruno's reservation lost the copy.")
                        .note("Bruno's reservation is ACTIVE and holds nothing, so Clara got the copy too.")
                        .build());
    }

    // ---- the better versions --------------------------------------------------------------------

    @Bean
    Scenario collectExpiryLocked(LockingExpiryTransitions locking) {
        return new SimpleScenario("collect-expiry-locked", LESSON,
                "S2, option A: the same two orderings; member, reservation and copies locked, clock read after",
                scenario -> collectExpiry(scenario, locking::collect, locking)
                        .note("Exactly one winner in every round, and every fact agrees with it.")
                        .note("The reservation is loaded for the first time under its lock (SELECT ... FOR UPDATE "
                                + "in the SQL log) and the clock is read after the lock: the second transaction to "
                                + "arrive sees the first one's committed status, and the domain rule refuses it.")
                        .build());
    }

    @Bean
    Scenario collectExpiryConditional(ConditionalExpiryTransitions conditional) {
        return new SimpleScenario("collect-expiry-conditional", LESSON,
                "S2, option B: the same two orderings; a conditional UPDATE on the reservation decides",
                scenario -> collectExpiry(scenario, conditional::collect, conditional)
                        .note("UPDATE reservation SET status=... WHERE id=? AND status='ACTIVE' AND expires_at > ?: "
                                + "1 row, this transaction won; 0 rows, someone else decided first.")
                        .note("The second UPDATE waits for the first one's row lock, then PostgreSQL re-evaluates "
                                + "the WHERE on the committed row. The time rule now lives in Java and in the query.")
                        .build());
    }

    private ScenarioReport.Builder collectExpiry(Scenario scenario, ExpiryRaces.Collect collect,
                                                 ReservationExpirer expirer) {
        var collectionFirst = rounds(ROUNDS, () -> races.collectionCommitsFirst(collect, expirer));
        var expiryFirst = rounds(ROUNDS, () -> races.expiryCommitsFirst(collect, expirer));
        return ScenarioReport.of(scenario)
                .result("rounds per ordering", ROUNDS)
                .result("required, collection commits first", REQUIRED_COLLECTION_FIRST)
                .result("measured, collection commits first", ExpiryRaces.tally(collectionFirst))
                .result("required, expiry commits first", REQUIRED_EXPIRY_FIRST)
                .result("measured, expiry commits first", ExpiryRaces.tally(expiryFirst));
    }

    @Bean
    Scenario cancelLocked(LockingExpiryTransitions locking) {
        return new SimpleScenario("cancel-locked", LESSON,
                "S3, better: the late cancel reads only scalar keys before its locks",
                scenario -> ScenarioReport.of(scenario)
                        .result("rounds", SHORT_ROUNDS)
                        .result("measured", ExpiryRaces.tally(rounds(SHORT_ROUNDS,
                                () -> races.staleCancel(locking::cancel, locking::collect))))
                        .note("Before the lock it reads a projection (reservation id, member id), not the entity: "
                                + "the persistence context stays empty, and the Reservation is loaded for the first "
                                + "time under the lock, with the committed COLLECTED status. 409, nothing changes.")
                        .build());
    }

    @Bean
    Scenario sweepAfterRereserveOwned(LockingExpiryTransitions locking) {
        return new SimpleScenario("sweep-after-rereserve-owned", LESSON,
                "S4, better: the late sweep releases only the copies Anna's reservation still holds",
                scenario -> {
                    ExpiryRaces.LateSweep late = races.lateSweep(locking);
                    return ScenarioReport.of(scenario)
                            .result("sweep", late.sweep())
                            .result("Anna's reservation", late.anna())
                            .result("Bruno's reservation", late.bruno())
                            .result("copy N0002", late.copy() + ", held by " + late.copyHeldBy())
                            .result("Clara asks for N0002", late.claraReserves())
                            .result("live reservations promising N0002", late.liveReservationsOfTheCopy())
                            .note("Copy.releaseFrom(anna) answers false: the copy belongs to Bruno now. That is not "
                                    + "an error, it is lazy expiry and the sweep agreeing.")
                            .build();
                });
    }

    @Bean
    Scenario sweepOrdered(LockingExpiryTransitions locking) {
        return new SimpleScenario("sweep-ordered", LESSON,
                "S5, better: one lock order everywhere (member, reservation, copies) and a sweep that never waits",
                scenario -> ScenarioReport.of(scenario)
                        .result("rounds", SHORT_ROUNDS)
                        .result("measured", ExpiryRaces.tally(rounds(SHORT_ROUNDS, () -> races.deadlock(locking))))
                        .note("No deadlock: the sweep asks for the member first, finds it locked by Anna's request "
                                + "(SKIP LOCKED) and skips the reservation at once. Anna never waits.")
                        .note("Skipping is safe: the old reservation stays ACTIVE and expired in the database, and "
                                + "the next run (expireAgain) expires it. The cost is bookkeeping a little later.")
                        .build());
    }

    @Bean
    Scenario sweepTwoWorkersSkipLocked(LockingExpiryTransitions locking) {
        return new SimpleScenario("sweep-two-workers-skip-locked", LESSON,
                "S6, better: two instances sweep the same " + WORKERS_RESERVATIONS + " reservations, SKIP LOCKED",
                scenario -> {
                    SweepRaces.TwoWorkers workers = sweeps.twoWorkers(locking, WORKERS_RESERVATIONS);
                    return ScenarioReport.of(scenario)
                            .result("worker 1", workers.first())
                            .result("worker 2", workers.second())
                            .result("expire() returned true", workers.expireReturnedTrue())
                            .result("stored afterwards", workers.outcome())
                            .note("For each reservation one worker takes the locks; the other skips it (row locked) "
                                    + "or finds it already EXPIRED. Each reservation expires once, one notice each.")
                            .build();
                });
    }

    @Bean
    Scenario expiryNoticeAfterCommit(LockingExpiryTransitions locking) {
        return new SimpleScenario("expiry-notice-after-commit", LESSON,
                "S6b, better: the same failure after the notice, with the notice sent after the commit",
                scenario -> {
                    SweepRaces.NoticeRollback rollback = sweeps.noticeThenRollback(locking);
                    return ScenarioReport.of(scenario)
                            .result("sweep", rollback.sweep())
                            .result("stored afterwards", rollback.outcome())
                            .note("The notice is published as an event and sent by a "
                                    + "@TransactionalEventListener(phase = AFTER_COMMIT): the rolled-back expiry sent "
                                    + "nothing, and the reservation is still a candidate for the next run.")
                            .note("At most once: if the application died right after the commit, that notice would "
                                    + "be lost. Exactly once needs an outbox table written in the same transaction.")
                            .build();
                });
    }

    @Bean
    Scenario sweepBatched(LockingExpiryTransitions locking) {
        return new SimpleScenario("sweep-batched", LESSON,
                "S7, better: the same backlog of " + BACKLOG + ", pages of " + ExpirySweep.PAGE_SIZE
                        + ", one transaction per reservation",
                scenario -> {
                    SweepRaces.Backlog backlog = sweeps.backlog(
                            () -> SweepRaces.untilDone(sweeps.boundedSweep(locking)), BACKLOG);
                    return ScenarioReport.of(scenario)
                            .result("expired reservations", backlog.reservations())
                            .result("sweep (runs until nothing is due)", backlog.sweep())
                            .result("time", backlog.time())
                            .sql("sweep", backlog.sql())
                            .result("heap peak increase", backlog.heapPeak())
                            .result("stored afterwards", backlog.outcome())
                            .result("Bruno reserves a copy the sweep released, during the sweep", backlog.brunoReserves())
                            .result("Bruno waited", backlog.brunoWaited())
                            .result("sweep with one failure in the middle", backlog.afterAFailure())
                            .result("stored after that sweep", backlog.outcomeAfterAFailure())
                            .note("Each expiry commits on its own: its locks last milliseconds, so Bruno does not "
                                    + "wait for the sweep.")
                            .note("Compare with sweep-one-transaction: this one is slower in total (more statements "
                                    + "per reservation: key, member lock, reservation lock, copy locks; and one commit "
                                    + "each). What it buys is not throughput: nobody waits for it, a failure costs one "
                                    + "reservation, and no notice is sent for an expiry that did not happen.")
                            .note("The failing reservation rolled back alone, sent nothing, and the next run "
                                    + "expired it. With one run every 60 s, " + BACKLOG + " reservations take "
                                    + BACKLOG / ExpirySweep.PAGE_SIZE + " runs: the interval is not a maximum delay.")
                            .build();
                });
    }

    @Bean
    Scenario returnConditional(ConditionalLoanReturnService conditionalReturn, LockingExpiryTransitions locking) {
        return new SimpleScenario("return-conditional", LESSON,
                "S8, better: the same retried return; the loan row decides with a conditional update",
                scenario -> ScenarioReport.of(scenario)
                        .result("rounds", SHORT_ROUNDS)
                        .result("measured", ExpiryRaces.tally(rounds(SHORT_ROUNDS,
                                () -> races.doubleReturn(conditionalReturn::giveBack, locking::collect))))
                        .note("UPDATE loan SET returned=true WHERE id=? AND returned=false: 1 row for the first "
                                + "return, 0 for the retry, which then just answers with the returned loan.")
                        .note("Only the winner touches the copy, and only its status, and only if it is ON_LOAN: "
                                + "Bruno keeps his copy, and Clara is refused.")
                        .build());
    }

    static <T> List<T> rounds(int rounds, java.util.function.Supplier<T> round) {
        return IntStream.range(0, rounds).mapToObj(i -> round.get()).toList();
    }
}
