package cat.tecnocampus.library.shop;

import cat.tecnocampus.library.catalogue.ProjectionCatalogueService;
import cat.tecnocampus.library.domain.ShopExceptions.NothingAvailableException;
import cat.tecnocampus.library.domain.ShopExceptions.PurchaseInProgressException;
import cat.tecnocampus.library.receipt.ReceiptDispatcher;
import cat.tecnocampus.library.scenario.ConcurrentRun;
import cat.tecnocampus.library.scenario.ConcurrentRun.Attempt;
import cat.tecnocampus.library.scenario.RaceWindow;
import cat.tecnocampus.library.scenario.Scenario;
import cat.tecnocampus.library.scenario.ScenarioReport;
import cat.tecnocampus.library.scenario.SimpleScenario;
import cat.tecnocampus.library.scenario.TransitionPauses;
import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.CannotCreateTransactionException;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Selling-books scenarios. Each lesson has a naive and a better scenario that run the same
 * requests on the same data and print the same facts. Every scenario first resets the shop and
 * the gateway ({@link ShopDemo#reset()}).
 */
@Configuration
class ShopScenarios {

    private static final String LESSON = "selling-books";

    // Lesson 1: ten simultaneous requests with one key.
    private static final int SAME_KEY_REQUESTS = 10;

    // Lesson 2: more purchases waiting for a slow provider than the pool has connections (60).
    private static final int POOL_PURCHASES = 80;
    private static final long POOL_PROVIDER_DELAY_MS = 1_500;
    private static final long POOL_PROBE_AFTER_MS = 300;
    // The catalogue read is measured as a client that gives up after this long would see it.
    private static final long CLIENT_PATIENCE_MS = 500;

    // Lesson 3: the provider answers after the client has given up (read timeout 2 s).
    private static final long LATE_PROVIDER_DELAY_MS = 3_000;

    private final ShopDemo demo;
    private final NaivePurchaseService naive;
    private final PurchaseService better;
    private final RaceWindow raceWindow;
    private final TransitionPauses pauses;
    private final ReceiptDispatcher dispatcher;
    private final ProjectionCatalogueService catalogue;
    private final DataSource dataSource;

    ShopScenarios(ShopDemo demo, NaivePurchaseService naive, PurchaseService better, RaceWindow raceWindow,
                  TransitionPauses pauses, ReceiptDispatcher dispatcher, ProjectionCatalogueService catalogue,
                  DataSource dataSource) {
        this.demo = demo;
        this.naive = naive;
        this.better = better;
        this.raceWindow = raceWindow;
        this.pauses = pauses;
        this.dispatcher = dispatcher;
        this.catalogue = catalogue;
        this.dataSource = dataSource;
    }

    // ---- Lesson 1: one key, ten requests ----------------------------------------------------

    @Bean
    Scenario purchaseKeyNaive() {
        return new SimpleScenario("purchase-key-naive", LESSON,
                "Ten simultaneous requests with the same Idempotency-Key: look for the key, create if missing",
                scenario -> sameKey(scenario, naive::purchase, true)
                        .note("Every request looked for the key before any of them had committed: none found it.")
                        .note("Ten purchases, ten charges, ten receipts and ten messages for one click.")
                        .build());
    }

    @Bean
    Scenario purchaseKeyBetter() {
        return new SimpleScenario("purchase-key-better", LESSON,
                "Ten simultaneous requests with the same Idempotency-Key: unique (member, key) row, then wait for the answer",
                scenario -> sameKey(scenario, better::purchase, false)
                        .note("One insert of the key committed; the other nine waited on the unique index, failed, "
                                + "and read the winner's purchase.")
                        .note("While the winner was charging, the others found its lease busy and waited for the answer.")
                        .build());
    }

    private ScenarioReport.Builder sameKey(Scenario scenario, Function<PurchaseCommand, PurchaseAnswer> buy,
                                           boolean raceTheCheck) {
        demo.reset();
        String member = "member01@library.test";
        PurchaseCommand command = new PurchaseCommand(member, "same-key", List.of(ShopDemo.isbn(1), ShopDemo.isbn(2)), "tok_ok");

        if (raceTheCheck) {
            raceWindow.arm(SAME_KEY_REQUESTS);
        }
        List<Attempt<PurchaseAnswer>> attempts;
        try {
            attempts = ConcurrentRun.run(SAME_KEY_REQUESTS, i -> buy.apply(command));
        } finally {
            raceWindow.disarm();
        }

        long distinctBodies = attempts.stream().filter(Attempt::succeeded).map(a -> a.value().body()).distinct().count();
        return ScenarioReport.of(scenario)
                .result("requests", SAME_KEY_REQUESTS)
                .result("answers", outcomes(attempts))
                .result("distinct answer bodies", distinctBodies)
                .result("purchases created", demo.purchasesOf(member))
                .result("charges at the gateway", demo.gateway().charges().size())
                .result("receipts", demo.receiptsOf(member))
                .result("messages received by the member", demo.mailsTo(member))
                .result("copies of book 1 taken", demo.taken(ShopDemo.isbn(1)))
                .result("latency", ConcurrentRun.latency(attempts));
    }

    // ---- Lesson 2: the slow provider and the connection pool --------------------------------

    @Bean
    Scenario purchasePoolNaive() {
        return new SimpleScenario("purchase-pool-naive", LESSON,
                "80 purchases while the provider takes 1.5 s; the call to the provider is inside the transaction",
                scenario -> pool(scenario, naive::purchase)
                        .note("Each purchase keeps its database connection while it waits for the provider: "
                                + "60 connections busy doing nothing, the other requests wait for one.")
                        .note("The catalogue never talks to the provider, and it cannot answer either: "
                                + "it waits in the same queue for a connection.")
                        .build());
    }

    @Bean
    Scenario purchasePoolBetter() {
        return new SimpleScenario("purchase-pool-better", LESSON,
                "80 purchases while the provider takes 1.5 s; short transactions, the call outside any transaction",
                scenario -> pool(scenario, better::purchase)
                        .note("While the purchases wait for the provider, they hold no connection: the pool is free.")
                        .note("Same pool, same provider, same timeouts: only the lifetime of the connection changed.")
                        .build());
    }

    private ScenarioReport.Builder pool(Scenario scenario, Function<PurchaseCommand, PurchaseAnswer> buy) {
        demo.reset();
        demo.gateway().slowResponseMillis(POOL_PROVIDER_DELAY_MS);
        HikariDataSource hikari = hikari();
        HikariConfigMXBean config = hikari.getHikariConfigMXBean();
        HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
        CompletableFuture<List<Attempt<PurchaseAnswer>>> running = CompletableFuture.supplyAsync(
                () -> ConcurrentRun.run(POOL_PURCHASES, i -> buy.apply(new PurchaseCommand(
                        "reader%03d@library.test".formatted(i + 1), "pool-" + i,
                        List.of(ShopDemo.isbn(1 + i % 40)), "tok_slow"))));
        sleep(POOL_PROBE_AFTER_MS);
        String connections = pool.getActiveConnections() + " active, " + pool.getIdleConnections() + " idle, "
                + pool.getThreadsAwaitingConnection() + " threads waiting for one";
        String probe = probeCatalogue();
        List<Attempt<PurchaseAnswer>> attempts = running.join();

        return ScenarioReport.of(scenario)
                .result("purchases", POOL_PURCHASES)
                .result("provider delay", POOL_PROVIDER_DELAY_MS + " ms")
                .result("pool", config.getMaximumPoolSize() + " connections, checkout timeout "
                        + config.getConnectionTimeout() + " ms")
                .result("pool while the purchases wait for the provider", connections)
                .result("unrelated catalogue read at that moment", probe)
                .result("answers", outcomes(attempts))
                .result("charges at the gateway", demo.gateway().charges().size())
                .result("latency", ConcurrentRun.latency(attempts));
    }

    /**
     * Reads the catalogue (which never talks to the provider) and reports it as a client that waits
     * at most {@value #CLIENT_PATIENCE_MS} ms would see it, plus when it really answered.
     */
    private String probeCatalogue() {
        long start = System.nanoTime();
        CompletableFuture<Integer> read = CompletableFuture.supplyAsync(() -> catalogue.list().size());
        try {
            int books = read.get(CLIENT_PATIENCE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            return "%d books in %s".formatted(books, millisSince(start));
        } catch (java.util.concurrent.TimeoutException slow) {
            String verdict = "NO ANSWER within %d ms".formatted(CLIENT_PATIENCE_MS);
            try {
                read.join();
                return verdict + " (it answered after %s, once a purchase gave a connection back)".formatted(millisSince(start));
            } catch (RuntimeException e) {
                return verdict + " (it failed after %s: %s)".formatted(millisSince(start), e.getClass().getSimpleName());
            }
        } catch (Exception e) {
            return "FAILED after %s: %s".formatted(millisSince(start), e.getClass().getSimpleName());
        }
    }

    private HikariDataSource hikari() {
        try {
            return dataSource.unwrap(HikariDataSource.class);
        } catch (SQLException e) {
            throw new IllegalStateException("The pool scenarios need HikariCP", e);
        }
    }

    // ---- Lesson 3: the late answer -----------------------------------------------------------

    @Bean
    Scenario purchaseLateAnswerNaive() {
        return new SimpleScenario("purchase-late-answer-naive", LESSON,
                "The provider charges the card but answers after 3 s; the shop gives up at 2 s and treats it as a failure",
                scenario -> lateAnswer(scenario, naive::purchase)
                        .note("The shop said 'payment failed' and put the book back on sale; the provider charged the card.")
                        .note("A retry with the same key finds the failed purchase: the member paid and has nothing.")
                        .build());
    }

    @Bean
    Scenario purchaseLateAnswerBetter() {
        return new SimpleScenario("purchase-late-answer-better", LESSON,
                "The provider charges the card but answers after 3 s; the shop gives up at 2 s and treats it as unknown",
                scenario -> lateAnswer(scenario, better::purchase)
                        .note("A timeout proves nothing: RECONCILE (202), and the book stays taken.")
                        .note("The retry sends the same provider key; the provider replays its stored answer: "
                                + "PAID, one receipt, one charge.")
                        .build());
    }

    private ScenarioReport.Builder lateAnswer(Scenario scenario, Function<PurchaseCommand, PurchaseAnswer> buy) {
        demo.reset();
        demo.gateway().slowResponseMillis(LATE_PROVIDER_DELAY_MS);
        String member = "member01@library.test";
        PurchaseCommand command = new PurchaseCommand(member, "late-1", List.of(ShopDemo.isbn(1)), "tok_slow");

        long start = System.nanoTime();
        Attempt<PurchaseAnswer> first = once(() -> buy.apply(command));
        String firstTime = millisSince(start);
        UUID purchaseId = first.succeeded() ? first.value().body().id() : null;
        String afterFirst = purchaseId == null ? "no purchase" : demo.status(purchaseId);
        int takenAfterFirst = demo.taken(ShopDemo.isbn(1));

        sleep(LATE_PROVIDER_DELAY_MS - 1_500);          // the provider finishes its 3 s and charges
        long chargesMeanwhile = demo.gateway().charges().size();

        Attempt<PurchaseAnswer> retry = once(() -> buy.apply(command));
        return ScenarioReport.of(scenario)
                .result("first request", outcome(first) + " after " + firstTime)
                .result("purchase after the first request", afterFirst)
                .result("copies taken after the first request", takenAfterFirst)
                .result("charges at the gateway once it has answered", chargesMeanwhile)
                .result("retry with the same key", outcome(retry))
                .result("purchase after the retry", purchaseId == null ? "no purchase" : demo.status(purchaseId))
                .result("copies taken after the retry", demo.taken(ShopDemo.isbn(1)))
                .result("receipts", demo.receiptsOf(member))
                .result("charges at the gateway at the end", demo.gateway().charges().size());
    }

    // ---- Lesson 4: a failure after the charge ------------------------------------------------

    @Bean
    Scenario receiptFailureNaive() {
        return new SimpleScenario("receipt-failure-naive", LESSON,
                "Something fails after the receipt is issued; the message was sent before the commit",
                scenario -> receiptFailure(scenario, naive::purchase)
                        .note("The transaction rolled back: no purchase, no receipt. The card was charged and the "
                                + "member was told about a receipt that does not exist.")
                        .build());
    }

    @Bean
    Scenario receiptFailureBetter() {
        return new SimpleScenario("receipt-failure-better", LESSON,
                "Something fails after the receipt is issued; receipt and message are written in one transaction, sent after the commit",
                scenario -> receiptFailure(scenario, better::purchase)
                        .note("The failure rolled back the receipt and its message together: nothing sent.")
                        .note("The provider had answered, so the purchase goes to RECONCILE, not FAILED; "
                                + "the retry settles it: one receipt, one message, one charge.")
                        .build());
    }

    private ScenarioReport.Builder receiptFailure(Scenario scenario, Function<PurchaseCommand, PurchaseAnswer> buy) {
        demo.reset();
        String member = "member02@library.test";
        PurchaseCommand command = new PurchaseCommand(member, "fail-1", List.of(ShopDemo.isbn(3)), "tok_ok");
        pauses.failAt(TransitionPauses.RECEIPT_ISSUED, demo.memberId(member));

        Attempt<PurchaseAnswer> first = once(() -> buy.apply(command));
        ScenarioReport.Builder report = ScenarioReport.of(scenario)
                .result("first request", outcome(first))
                .result("purchases of the member", demo.purchasesOf(member))
                .result("receipts", demo.receiptsOf(member))
                .result("messages received by the member", demo.mailsTo(member))
                .result("charges at the gateway", demo.gateway().charges().size())
                .result("copies of book 3 taken", demo.taken(ShopDemo.isbn(3)));

        Attempt<PurchaseAnswer> retry = once(() -> buy.apply(command));
        return report
                .result("retry with the same key", outcome(retry))
                .result("after the retry: purchases / receipts / messages / charges",
                        demo.purchasesOf(member) + " / " + demo.receiptsOf(member) + " / " + demo.mailsTo(member)
                                + " / " + demo.gateway().charges().size());
    }

    // ---- Lesson 5: a crash after the commit -------------------------------------------------

    @Bean
    Scenario receiptRestartBetter() {
        return new SimpleScenario("receipt-restart-better", LESSON,
                "The application dies right after the purchase commits, before sending the message; then it comes back",
                scenario -> {
                    demo.reset();
                    String member = "member03@library.test";
                    dispatcher.simulateCrashAfterCommit(true);    // "the process dies after the commit"
                    Attempt<PurchaseAnswer> purchase = once(() -> better.purchase(new PurchaseCommand(
                            member, "restart-1", List.of(ShopDemo.isbn(4)), "tok_ok")));
                    ScenarioReport.Builder report = ScenarioReport.of(scenario)
                            .result("purchase", outcome(purchase))
                            .result("before the restart: pending messages", demo.pendingMessages())
                            .result("before the restart: messages received", demo.mailsTo(member));

                    dispatcher.simulateCrashAfterCommit(false);   // the application is back; its job runs
                    int firstRun = dispatcher.deliverPending();
                    int secondRun = dispatcher.deliverPending();
                    return report
                            .result("job, first run: sent", firstRun)
                            .result("job, second run: sent", secondRun)
                            .result("after the restart: pending / sent", demo.pendingMessages() + " / " + demo.sentMessages())
                            .result("after the restart: messages received", demo.mailsTo(member))
                            .note("The message was committed with the receipt, so it survived the crash as a pending row.")
                            .note("The job sends it once; its next run finds nothing pending.")
                            .build();
                });
    }

    // ---- Every answer of the provider, and a partial purchase ---------------------------------

    @Bean
    Scenario purchaseOutcomes() {
        return new SimpleScenario("purchase-outcomes", LESSON,
                "One purchase per provider behaviour, one with a sold-out book, one with nothing in stock (better version)",
                scenario -> {
                    demo.reset();
                    Map<String, Object> rows = new LinkedHashMap<>();
                    rows.put("tok_ok, books 5 + sold-out 41 + 6", outcomeCase("member10", "tok_ok",
                            List.of(ShopDemo.isbn(5), ShopDemo.SOLD_OUT_ISBN, ShopDemo.isbn(6))));
                    rows.put("tok_reject, book 7", outcomeCase("member11", "tok_reject", List.of(ShopDemo.isbn(7))));
                    rows.put("tok_fail, book 8", outcomeCase("member12", "tok_fail", List.of(ShopDemo.isbn(8))));
                    rows.put("tok_pending, book 9", outcomeCase("member13", "tok_pending", List.of(ShopDemo.isbn(9))));
                    rows.put("tok_ok, sold-out 41 only", outcomeCase("member14", "tok_ok", List.of(ShopDemo.SOLD_OUT_ISBN)));
                    ScenarioReport.Builder report = ScenarioReport.of(scenario);
                    rows.forEach(report::result);
                    return report
                            .result("charges at the gateway", demo.gateway().charges().size())
                            .note("Declined and failed purchases gave their copies back; the pending one keeps them.")
                            .note("tok_fail: three calls, all 503, nothing recorded at the gateway.")
                            .build();
                });
    }

    private String outcomeCase(String member, String token, List<String> isbns) {
        Attempt<PurchaseAnswer> attempt = once(() -> better.purchase(
                new PurchaseCommand(member + "@library.test", "outcome-" + token + "-" + isbns.size(), isbns, token)));
        String taken = isbns.stream().filter(isbn -> !isbn.equals(ShopDemo.SOLD_OUT_ISBN))
                .map(isbn -> String.valueOf(demo.taken(isbn))).collect(Collectors.joining(", "));
        if (!attempt.succeeded()) {
            return outcome(attempt);
        }
        PurchaseResponse body = attempt.value().body();
        return String.format(Locale.ROOT, "%s; bought %d, skipped %s, total %.2f; copies taken: [%s]; receipt %s",
                outcome(attempt), body.lines().size(), body.skippedIsbns(), body.totalCents() / 100.0, taken,
                body.receipt() == null ? "none" : body.receipt().number());
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private static Attempt<PurchaseAnswer> once(java.util.function.Supplier<PurchaseAnswer> call) {
        return ConcurrentRun.run(1, i -> call.get()).getFirst();
    }

    private static Map<String, Long> outcomes(List<Attempt<PurchaseAnswer>> attempts) {
        return new TreeMap<>(attempts.stream().collect(Collectors.groupingBy(ShopScenarios::outcome, Collectors.counting())));
    }

    static String outcome(Attempt<PurchaseAnswer> attempt) {
        if (attempt.succeeded()) {
            return attempt.value().httpStatus() + " " + attempt.value().body().status();
        }
        Throwable error = attempt.error();
        if (error instanceof NothingAvailableException) {
            return "409 nothing available";
        }
        if (error instanceof PurchaseInProgressException) {
            return "503 purchase in progress";
        }
        if (error instanceof CannotCreateTransactionException) {
            return "500 no database connection";
        }
        if (error instanceof TransitionPauses.InjectedFailure) {
            return "500 injected failure";
        }
        return "500 " + error.getClass().getSimpleName();
    }

    private static String millisSince(long startNanos) {
        return String.format(Locale.ROOT, "%.0f ms", (System.nanoTime() - startNanos) / 1_000_000.0);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
