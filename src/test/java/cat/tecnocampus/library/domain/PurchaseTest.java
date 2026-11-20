package cat.tecnocampus.library.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The payment rules of a purchase, without a database or a provider. */
class PurchaseTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private static Purchase purchase() {
        List<PurchaseLine> lines = List.of(
                new PurchaseLine(UUID.randomUUID(), "978-84-000001", "Title 001", 950),
                new PurchaseLine(UUID.randomUUID(), "978-84-000002", "Title 002", 1000));
        return Purchase.awaitingPayment(UUID.randomUUID(), Fixtures.member(), "key-1", "fingerprint",
                lines, List.of("978-84-000041"), NOW);
    }

    private static UUID grantedLease(Purchase purchase, Instant now) {
        PaymentClaim claim = purchase.claimPayment(now);
        assertThat(claim.kind()).isEqualTo(PaymentClaim.Kind.GRANTED);
        return claim.lease();
    }

    @Test
    void aNewPurchaseFreezesItsLinesAndAddsThemUp() {
        // Given / When
        Purchase purchase = purchase();

        // Then
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.PAYMENT_PENDING);
        assertThat(purchase.getTotalCents()).isEqualTo(1950);
        assertThat(purchase.getCurrency()).isEqualTo("EUR");
        assertThat(purchase.getSkippedIsbns()).containsExactly("978-84-000041");
    }

    @Test
    void aPurchaseWithoutBooksIsRefused() {
        assertThatThrownBy(() -> Purchase.awaitingPayment(UUID.randomUUID(), Fixtures.member(), "k", "f",
                List.of(), List.of(), NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyOneAttemptAtATimeMayCharge() {
        // Given
        Purchase purchase = purchase();
        grantedLease(purchase, NOW);

        // When: a second request with the same key arrives while the first is charging
        PaymentClaim second = purchase.claimPayment(NOW.plusSeconds(1));

        // Then
        assertThat(second.kind()).isEqualTo(PaymentClaim.Kind.BUSY);
    }

    @Test
    void anAcceptedChargeSellsTheCopiesAndCannotChangeAgain() {
        // Given
        Purchase purchase = purchase();
        UUID lease = grantedLease(purchase, NOW);

        // When
        StockAction action = purchase.settle(lease, PaymentEvidence.accepted("ch_1"), NOW);

        // Then
        assertThat(action).isEqualTo(StockAction.SELL);
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.PAID);
        assertThat(purchase.getProviderRef()).isEqualTo("ch_1");
        assertThat(purchase.claimPayment(NOW.plusSeconds(1)).kind()).isEqualTo(PaymentClaim.Kind.SETTLED);
        assertThat(purchase.settle(lease, PaymentEvidence.of(PaymentEvidence.Outcome.DECLINED), NOW))
                .isEqualTo(StockAction.NONE);
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.PAID);
    }

    @Test
    void aDeclineReleasesTheCopies() {
        Purchase purchase = purchase();
        UUID lease = grantedLease(purchase, NOW);

        StockAction action = purchase.settle(lease, PaymentEvidence.of(PaymentEvidence.Outcome.DECLINED), NOW);

        assertThat(action).isEqualTo(StockAction.RELEASE);
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.DECLINED);
    }

    @Test
    void anUnrecordedFailureReleasesTheCopiesWhenNoEarlierAttemptIsInDoubt() {
        Purchase purchase = purchase();
        UUID lease = grantedLease(purchase, NOW);

        StockAction action = purchase.settle(lease, PaymentEvidence.of(PaymentEvidence.Outcome.UNRECORDED_FAILURE), NOW);

        assertThat(action).isEqualTo(StockAction.RELEASE);
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.PAYMENT_FAILED);
    }

    @Test
    void anUnknownOutcomeKeepsTheCopiesAndAsksForReconciliation() {
        // Given
        Purchase purchase = purchase();
        UUID lease = grantedLease(purchase, NOW);

        // When: a timeout
        StockAction action = purchase.settle(lease, PaymentEvidence.of(PaymentEvidence.Outcome.UNKNOWN), NOW);

        // Then
        assertThat(action).isEqualTo(StockAction.RETAIN);
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.RECONCILE);
        assertThat(purchase.getReviewAfter()).isEqualTo(NOW.plus(Purchase.REVIEW_AFTER));
    }

    @Test
    void afterAnUnknownOutcomeA503ProvesNothing() {
        // Given: the first attempt timed out (it may have charged)
        Purchase purchase = purchase();
        purchase.settle(grantedLease(purchase, NOW), PaymentEvidence.of(PaymentEvidence.Outcome.UNKNOWN), NOW);

        // When: the retry gets 503 three times
        UUID retry = grantedLease(purchase, NOW.plusSeconds(30));
        StockAction action = purchase.settle(retry, PaymentEvidence.of(PaymentEvidence.Outcome.UNRECORDED_FAILURE), NOW);

        // Then: still unknown, never released
        assertThat(action).isEqualTo(StockAction.RETAIN);
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.RECONCILE);
    }

    @Test
    void aRetryCanSettleAReconciledPurchase() {
        Purchase purchase = purchase();
        purchase.settle(grantedLease(purchase, NOW), PaymentEvidence.of(PaymentEvidence.Outcome.UNKNOWN), NOW);

        UUID retry = grantedLease(purchase, NOW.plusSeconds(30));
        StockAction action = purchase.settle(retry, PaymentEvidence.accepted("ch_late"), NOW.plusSeconds(30));

        assertThat(action).isEqualTo(StockAction.SELL);
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.PAID);
    }

    @Test
    void anAbandonedAttemptLosesItsRightToDecideAndCountsAsUnknown() {
        // Given: an attempt that never came back (the process died while charging)
        Purchase purchase = purchase();
        UUID abandoned = grantedLease(purchase, NOW);

        // When: its lease runs out and another request takes over
        UUID takeover = grantedLease(purchase, NOW.plus(Purchase.PAYMENT_LEASE));

        // Then: the old attempt changes nothing, even if it wakes up
        assertThat(purchase.settle(abandoned, PaymentEvidence.of(PaymentEvidence.Outcome.DECLINED), NOW))
                .isEqualTo(StockAction.NONE);
        assertThat(purchase.isEarlierAttemptUnknown()).isTrue();
        // and a 503 for the new one cannot release copies the old one may have paid for
        assertThat(purchase.settle(takeover, PaymentEvidence.of(PaymentEvidence.Outcome.UNRECORDED_FAILURE), NOW))
                .isEqualTo(StockAction.RETAIN);
    }

    @Test
    void aLocalFailureAfterTheProviderAnsweredIsReconciled() {
        Purchase purchase = purchase();
        UUID lease = grantedLease(purchase, NOW);

        purchase.localFailure(lease, NOW);

        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.RECONCILE);
        assertThat(purchase.claimPayment(NOW.plusSeconds(1)).kind()).isEqualTo(PaymentClaim.Kind.GRANTED);
    }

    @Test
    void theNaiveVersionTurnsAnyNon201IntoAFailure() {
        Purchase purchase = purchase();

        purchase.markFailed();

        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.PAYMENT_FAILED);
        assertThatThrownBy(() -> purchase.markPaid("ch_1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aReceiptExistsOnlyForAPaidPurchase() {
        // Given
        Purchase purchase = purchase();

        // When / Then
        assertThatThrownBy(() -> Receipt.issueFor(purchase, NOW)).isInstanceOf(IllegalStateException.class);
        purchase.settle(grantedLease(purchase, NOW), PaymentEvidence.accepted("ch_9"), NOW);
        Receipt receipt = Receipt.issueFor(purchase, NOW.plus(Duration.ofSeconds(1)));
        assertThat(receipt.getTotalCents()).isEqualTo(1950);
        assertThat(receipt.getProviderRef()).isEqualTo("ch_9");
        assertThat(receipt.getNumber()).startsWith("R-");
        assertThat(ReceiptMessage.text(receipt, purchase)).contains("Title 001 (9.50)", "Total 19.50 EUR", "ch_9");
    }
}
