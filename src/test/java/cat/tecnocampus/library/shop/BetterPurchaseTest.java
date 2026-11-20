package cat.tecnocampus.library.shop;

import cat.tecnocampus.library.GatewayContainerConfiguration;
import cat.tecnocampus.library.TestcontainersConfiguration;
import cat.tecnocampus.library.domain.NotFoundException;
import cat.tecnocampus.library.domain.PurchaseStatus;
import cat.tecnocampus.library.domain.ShopExceptions.IdempotencyKeyReusedException;
import cat.tecnocampus.library.domain.ShopExceptions.NothingAvailableException;
import cat.tecnocampus.library.receipt.ReceiptDispatcher;
import cat.tecnocampus.library.scenario.ConcurrentRun;
import cat.tecnocampus.library.scenario.ConcurrentRun.Attempt;
import cat.tecnocampus.library.scenario.TransitionPauses;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static cat.tecnocampus.library.shop.ShopDemo.isbn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The better purchase against PostgreSQL and the real gateway. Not {@code @Transactional}: every
 * step commits in its own transaction, as in production, and the facts are read afterwards.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, GatewayContainerConfiguration.class})
class BetterPurchaseTest {

    private static final String ANNA = "member01@library.test";

    @Autowired PurchaseService purchases;
    @Autowired PurchaseReads reads;
    @Autowired ShopDemo demo;
    @Autowired TransitionPauses pauses;
    @Autowired ReceiptDispatcher dispatcher;
    @Autowired DataSource dataSource;

    @BeforeEach
    void freshShop() {
        demo.reset();
    }

    private static PurchaseCommand buy(String key, String token, String... isbns) {
        return new PurchaseCommand(ANNA, key, List.of(isbns), token);
    }

    // ---- UC-18: the purchase and its idempotency -------------------------------------------------

    @Test
    void soldOutBooksAreSkippedAndTheOthersAreBought() {
        // When
        PurchaseAnswer answer = purchases.purchase(buy("k1", "tok_ok", isbn(1), ShopDemo.SOLD_OUT_ISBN, isbn(2)));

        // Then
        assertThat(answer.httpStatus()).isEqualTo(201);
        assertThat(answer.body().lines()).extracting(PurchaseResponse.Line::isbn).containsExactly(isbn(1), isbn(2));
        assertThat(answer.body().skippedIsbns()).containsExactly(ShopDemo.SOLD_OUT_ISBN);
        assertThat(answer.body().totalCents()).isEqualTo(950 + 1000);
        assertThat(demo.taken(isbn(1))).isEqualTo(1);
        assertThat(demo.gateway().charges()).singleElement()
                .satisfies(charge -> assertThat(charge.amountCents()).isEqualTo(1950));
    }

    @Test
    void nothingInStockCreatesNothingAndLeavesTheKeyUnused() {
        // When / Then
        assertThatThrownBy(() -> purchases.purchase(buy("k1", "tok_ok", ShopDemo.SOLD_OUT_ISBN)))
                .isInstanceOf(NothingAvailableException.class);
        assertThat(demo.purchasesOf(ANNA)).isZero();
        assertThat(demo.gateway().charges()).isEmpty();
        // the key was rolled back with the rest: it can be used for another purchase
        assertThat(purchases.purchase(buy("k1", "tok_ok", isbn(3))).httpStatus()).isEqualTo(201);
    }

    @Test
    void anUnknownBookIsRefusedBeforeTakingAnything() {
        assertThatThrownBy(() -> purchases.purchase(buy("k1", "tok_ok", isbn(1), "978-00-000000")))
                .isInstanceOf(NotFoundException.class);
        assertThat(demo.taken(isbn(1))).isZero();
    }

    @Test
    void aRetryWithTheSameKeyGetsTheOriginalAnswer() {
        // Given
        PurchaseAnswer first = purchases.purchase(buy("k1", "tok_ok", isbn(1)));

        // When
        PurchaseAnswer retry = purchases.purchase(buy("k1", "tok_ok", isbn(1)));

        // Then
        assertThat(retry).isEqualTo(first);
        assertThat(demo.purchasesOf(ANNA)).isEqualTo(1);
        assertThat(demo.gateway().charges()).hasSize(1);
    }

    @Test
    void theSameKeyWithAnotherBodyIsRefused() {
        purchases.purchase(buy("k1", "tok_ok", isbn(1)));

        assertThatThrownBy(() -> purchases.purchase(buy("k1", "tok_ok", isbn(2))))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void tenSimultaneousSubmissionsWithOneKeyMakeOnePurchaseOneChargeAndTenIdenticalAnswers() {
        // Given
        PurchaseCommand command = buy("same", "tok_ok", isbn(1), isbn(2));

        // When
        List<Attempt<PurchaseAnswer>> attempts = ConcurrentRun.run(10, i -> purchases.purchase(command));

        // Then
        assertThat(attempts).allMatch(Attempt::succeeded);
        assertThat(attempts.stream().map(Attempt::value).distinct()).hasSize(1);
        assertThat(attempts.getFirst().value().httpStatus()).isEqualTo(201);
        assertThat(demo.purchasesOf(ANNA)).isEqualTo(1);
        assertThat(demo.gateway().charges()).hasSize(1);
        assertThat(demo.receiptsOf(ANNA)).isEqualTo(1);
        assertThat(demo.mailsTo(ANNA)).isEqualTo(1);
        assertThat(demo.taken(isbn(1))).isEqualTo(1);
    }

    // ---- UC-19: the provider's answers ----------------------------------------------------------

    @Test
    void aDeclineFailsThePurchaseReleasesTheCopiesOnceAndIsReplayed() {
        // When
        PurchaseAnswer answer = purchases.purchase(buy("k1", "tok_reject", isbn(1)));
        PurchaseAnswer retry = purchases.purchase(buy("k1", "tok_reject", isbn(1)));

        // Then
        assertThat(answer.httpStatus()).isEqualTo(402);
        assertThat(answer.body().status()).isEqualTo(PurchaseStatus.DECLINED);
        assertThat(retry).isEqualTo(answer);
        assertThat(demo.taken(isbn(1))).isZero();      // released once, not twice
        assertThat(demo.receiptsOf(ANNA)).isZero();
        assertThat(demo.gateway().charges()).singleElement()      // recorded as REJECTED: no money taken
                .satisfies(charge -> assertThat(charge.status()).isEqualTo("REJECTED"));
    }

    @Test
    void aGatewayFailureFailsThePurchaseAndReleasesTheCopiesWithoutRetrying() {
        PurchaseAnswer answer = purchases.purchase(buy("k1", "tok_fail", isbn(1)));
        PurchaseAnswer retry = purchases.purchase(buy("k1", "tok_fail", isbn(1)));

        assertThat(answer.httpStatus()).isEqualTo(503);
        assertThat(answer.body().status()).isEqualTo(PurchaseStatus.PAYMENT_FAILED);
        assertThat(retry).isEqualTo(answer);           // settled: the same key does not charge again
        assertThat(demo.taken(isbn(1))).isZero();
        assertThat(demo.receiptsOf(ANNA)).isZero();
        assertThat(demo.gateway().charges()).isEmpty();
    }

    @Test
    void aSuccessfulChargeIsTakenExactlyOnce() {
        PurchaseAnswer answer = purchases.purchase(buy("k1", "tok_ok", isbn(1)));
        purchases.purchase(buy("k1", "tok_ok", isbn(1)));

        assertThat(answer.httpStatus()).isEqualTo(201);
        assertThat(demo.gateway().charges()).singleElement()
                .satisfies(charge -> assertThat(charge.reference()).isEqualTo(answer.body().id().toString()));
        assertThat(demo.taken(isbn(1))).isEqualTo(1);
    }

    @Test
    void withAProviderFailingThirtyPercentNoCopyIsLostAndNoChargeDuplicated() {
        // Given
        demo.gateway().failureRate(0.3);

        // When: twenty purchases, one book each
        List<PurchaseAnswer> answers = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            answers.add(purchases.purchase(buy("k" + i, "tok_ok", isbn(1 + i))));
        }

        // Then: each one is paid or failed; a paid one keeps its copy, a failed one gave it back
        assertThat(answers).allMatch(a -> a.httpStatus() == 201 || a.httpStatus() == 503);
        for (int i = 0; i < 20; i++) {
            assertThat(demo.taken(isbn(1 + i))).isEqualTo(answers.get(i).httpStatus() == 201 ? 1 : 0);
        }
        long paid = answers.stream().filter(a -> a.httpStatus() == 201).count();
        assertThat(demo.gateway().charges()).hasSize((int) paid);
    }

    @Test
    void noDatabaseConnectionIsHeldWhileWaitingForTheProvider() throws Exception {
        // Given
        demo.gateway().slowResponseMillis(1_500);
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);

        // When: ten purchases are waiting for the provider
        CompletableFuture<List<Attempt<PurchaseAnswer>>> running = CompletableFuture.supplyAsync(() ->
                ConcurrentRun.run(10, i -> purchases.purchase(new PurchaseCommand(
                        "member%02d@library.test".formatted(i + 20), "pool-" + i, List.of(isbn(1)), "tok_slow"))));
        Thread.sleep(700);
        int activeWhileWaiting = pool.getHikariPoolMXBean().getActiveConnections();

        // Then
        assertThat(activeWhileWaiting).isZero();
        assertThat(running.get()).allMatch(a -> a.succeeded() && a.value().httpStatus() == 201);
    }

    // ---- UC-20: receipt and message -------------------------------------------------------------

    @Test
    void aPaidPurchaseHasOneReceiptAndOneMessage() {
        PurchaseAnswer answer = purchases.purchase(buy("k1", "tok_ok", isbn(1)));

        assertThat(answer.body().receipt().number()).startsWith("R-");
        assertThat(reads.get(answer.body().id()).receipt()).isEqualTo(answer.body().receipt());
        assertThat(demo.mailsTo(ANNA)).isEqualTo(1);
        assertThat(demo.pendingMessages()).isZero();
    }

    @Test
    void aFailureAfterTheChargeIssuesNothingAndSendsNothing() {
        // Given
        pauses.failAt(TransitionPauses.RECEIPT_ISSUED, demo.memberId(ANNA));

        // When
        PurchaseAnswer answer = purchases.purchase(buy("k1", "tok_ok", isbn(1)));

        // Then: nothing was recorded; the purchase is still pending, with its copy, without receipt or message
        assertThat(answer.httpStatus()).isEqualTo(202);
        assertThat(answer.body().status()).isEqualTo(PurchaseStatus.PAYMENT_PENDING);
        assertThat(demo.receiptsOf(ANNA)).isZero();
        assertThat(demo.mailsTo(ANNA)).isZero();
        assertThat(demo.pendingMessages()).isZero();
        assertThat(demo.gateway().charges()).hasSize(1);
        assertThat(demo.taken(isbn(1))).isEqualTo(1);

        // and the retry settles it with the same charge (same provider key: the provider replays its 201)
        PurchaseAnswer retry = purchases.purchase(buy("k1", "tok_ok", isbn(1)));
        assertThat(retry.httpStatus()).isEqualTo(201);
        assertThat(demo.gateway().charges()).hasSize(1);
        assertThat(demo.mailsTo(ANNA)).isEqualTo(1);
    }

    @Test
    void aSimulatedRestartDeliversThePendingMessageOnce() {
        // Given: the application dies between the commit and the send
        dispatcher.simulateCrashAfterCommit(true);
        PurchaseAnswer answer = purchases.purchase(buy("k1", "tok_ok", isbn(1)));
        assertThat(answer.httpStatus()).isEqualTo(201);
        assertThat(demo.pendingMessages()).isEqualTo(1);
        assertThat(demo.mailsTo(ANNA)).isZero();

        // When: it comes back and its job runs, twice
        dispatcher.simulateCrashAfterCommit(false);
        int first = dispatcher.deliverPending();
        int second = dispatcher.deliverPending();

        // Then
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(demo.mailsTo(ANNA)).isEqualTo(1);
        assertThat(demo.mailbox().sentTo(ANNA).getFirst().text()).contains(answer.body().receipt().number());
    }
}
