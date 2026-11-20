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
        StockAction action = purchase.settle(lease, PaymentEvidence.accepted("ch_1"));

        // Then
        assertThat(action).isEqualTo(StockAction.SELL);
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.PAID);
        assertThat(purchase.getProviderRef()).isEqualTo("ch_1");
        assertThat(purchase.claimPayment(NOW.plusSeconds(1)).kind()).isEqualTo(PaymentClaim.Kind.SETTLED);
        assertThat(purchase.settle(lease, PaymentEvidence.of(PaymentEvidence.Outcome.DECLINED)))
                .isEqualTo(StockAction.NONE);
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.PAID);
    }

    @Test
    void aDeclineReleasesTheCopies() {
        Purchase purchase = purchase();
        UUID lease = grantedLease(purchase, NOW);

        StockAction action = purchase.settle(lease, PaymentEvidence.of(PaymentEvidence.Outcome.DECLINED));

        assertThat(action).isEqualTo(StockAction.RELEASE);
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.DECLINED);
    }

    @Test
    void aGatewayFailureReleasesTheCopies() {
        Purchase purchase = purchase();
        UUID lease = grantedLease(purchase, NOW);

        StockAction action = purchase.settle(lease, PaymentEvidence.of(PaymentEvidence.Outcome.FAILED));

        assertThat(action).isEqualTo(StockAction.RELEASE);
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.PAYMENT_FAILED);
        assertThat(purchase.claimPayment(NOW.plusSeconds(1)).kind()).isEqualTo(PaymentClaim.Kind.SETTLED);
    }

    @Test
    void anAbandonedAttemptLosesItsRightToDecideOnceItsLeaseRunsOut() {
        // Given: an attempt that never came back (the process died while waiting for the provider)
        Purchase purchase = purchase();
        UUID abandoned = grantedLease(purchase, NOW);
        assertThat(purchase.claimPayment(NOW.plus(Purchase.PAYMENT_LEASE).minusSeconds(1)).kind())
                .isEqualTo(PaymentClaim.Kind.BUSY);

        // When: its lease runs out and a retry with the same key takes over
        UUID takeover = grantedLease(purchase, NOW.plus(Purchase.PAYMENT_LEASE));

        // Then: the old attempt changes nothing, even if it wakes up; the new one decides
        assertThat(purchase.settle(abandoned, PaymentEvidence.of(PaymentEvidence.Outcome.DECLINED)))
                .isEqualTo(StockAction.NONE);
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.PAYMENT_PENDING);
        assertThat(purchase.settle(takeover, PaymentEvidence.accepted("ch_1"))).isEqualTo(StockAction.SELL);
        assertThat(purchase.getPaymentAttempts()).isEqualTo(2);
    }

    @Test
    void anAttemptThatCouldNotRecordItsAnswerGivesUpItsLeaseAtOnce() {
        // Given
        Purchase purchase = purchase();
        UUID lease = grantedLease(purchase, NOW);

        // When: recording the answer failed and rolled back
        purchase.abandonAttempt(lease);

        // Then: still pending, and a retry may charge right away
        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.PAYMENT_PENDING);
        assertThat(purchase.claimPayment(NOW.plusSeconds(1)).kind()).isEqualTo(PaymentClaim.Kind.GRANTED);
    }

    @Test
    void theNaiveVersionDecidesOnlyOnce() {
        Purchase purchase = purchase();

        purchase.markDeclined();

        assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.DECLINED);
        assertThatThrownBy(() -> purchase.markPaid("ch_1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aReceiptExistsOnlyForAPaidPurchase() {
        // Given
        Purchase purchase = purchase();

        // When / Then
        assertThatThrownBy(() -> Receipt.issueFor(purchase, NOW)).isInstanceOf(IllegalStateException.class);
        purchase.settle(grantedLease(purchase, NOW), PaymentEvidence.accepted("ch_9"));
        Receipt receipt = Receipt.issueFor(purchase, NOW.plus(Duration.ofSeconds(1)));
        assertThat(receipt.getTotalCents()).isEqualTo(1950);
        assertThat(receipt.getProviderRef()).isEqualTo("ch_9");
        assertThat(receipt.getNumber()).startsWith("R-");
        assertThat(ReceiptMessage.text(receipt, purchase)).contains("Title 001 (9.50)", "Total 19.50 EUR", "ch_9");
    }
}
