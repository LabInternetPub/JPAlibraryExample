package cat.tecnocampus.library.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A member's purchase of one or more books, and everything the shop knows about its payment.
 *
 * <p>The rules of the payment are here, not in the services: who may charge right now
 * ({@link #claimPayment}), what an answer of the provider means for the purchase and for its stock
 * ({@link #settle}), and what a failure after the provider answered means ({@link #localFailure}).
 * The services only lock the row, call these methods and apply the result.
 *
 * <p>There is no payment table: a purchase has at most one payment, and its state fits in a few
 * columns (status, provider reference, the lease of the attempt running now).
 */
@Entity
public class Purchase {

    public static final String CURRENCY = "EUR";

    /**
     * How long an attempt may take before another one is allowed to take over. Longer than the
     * worst attempt: 3 calls × (1 s connect + 3 s read) + backoff.
     */
    public static final Duration PAYMENT_LEASE = Duration.ofSeconds(15);

    /** When a person must look at a purchase whose payment is still unknown. */
    public static final Duration REVIEW_AFTER = Duration.ofHours(24);

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Member member;

    @Column(nullable = false, length = 100)
    private String idempotencyKey;

    // A hash of the request body: the same key with a different body is a client error, not a retry.
    @Column(nullable = false, length = 64)
    private String fingerprint;

    @ElementCollection
    @CollectionTable(name = "purchase_line", joinColumns = @JoinColumn(name = "purchase_id"))
    @OrderColumn(name = "position")
    private List<PurchaseLine> lines = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "purchase_skipped_isbn", joinColumns = @JoinColumn(name = "purchase_id"))
    @OrderColumn(name = "position")
    @Column(name = "isbn", nullable = false)
    private List<String> skippedIsbns = new ArrayList<>();

    @Column(nullable = false)
    private long totalCents;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PurchaseStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private String providerRef;

    // The attempt that may charge right now, and until when. Not a status: when an attempt dies,
    // its lease simply runs out and the next claim takes over.
    private UUID paymentLease;

    private Instant leaseUntil;

    // True once any attempt may have moved money without the shop knowing (unknown answer, or an
    // attempt that never came back). From then on, "the provider recorded nothing" proves nothing.
    private boolean earlierAttemptUnknown;

    private Instant reviewAfter;

    private int paymentAttempts;

    protected Purchase() {
    }

    private Purchase(UUID id, Member member, String idempotencyKey, String fingerprint,
                     List<PurchaseLine> lines, List<String> skippedIsbns, Instant now) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A purchase has at least one book");
        }
        this.id = Objects.requireNonNull(id);
        this.member = Objects.requireNonNull(member);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.fingerprint = Objects.requireNonNull(fingerprint);
        this.lines.addAll(lines);
        this.skippedIsbns.addAll(skippedIsbns);
        this.totalCents = lines.stream().mapToLong(PurchaseLine::getPriceCents).sum();
        this.currency = CURRENCY;
        this.status = PurchaseStatus.PAYMENT_PENDING;
        this.createdAt = Objects.requireNonNull(now);
    }

    /** A purchase whose stock has been taken and whose payment has not been decided yet. */
    public static Purchase awaitingPayment(UUID id, Member member, String idempotencyKey, String fingerprint,
                                           List<PurchaseLine> lines, List<String> skippedIsbns, Instant now) {
        return new Purchase(id, member, idempotencyKey, fingerprint, lines, skippedIsbns, now);
    }

    /** The same key with a different body is not a retry. */
    public boolean isSameRequest(String otherFingerprint) {
        return fingerprint.equals(otherFingerprint);
    }

    // ---- The better version ------------------------------------------------------------------

    /**
     * Asks for the right to charge now. Called with the row locked, so two requests with the same
     * key cannot both be granted.
     *
     * <ul>
     *   <li>settled: there is nothing to charge, the answer is already decided;</li>
     *   <li>another attempt holds a live lease: busy, wait for its answer;</li>
     *   <li>otherwise: a new lease with a fresh nonce. If an earlier lease is still recorded, that
     *       attempt never finished: it may have charged, so it counts as an unknown outcome.</li>
     * </ul>
     */
    public PaymentClaim claimPayment(Instant now) {
        if (status.isSettled()) {
            return PaymentClaim.settled();
        }
        if (paymentLease != null && now.isBefore(leaseUntil)) {
            return PaymentClaim.busy();
        }
        if (paymentLease != null) {
            earlierAttemptUnknown = true;
        }
        paymentLease = UUID.randomUUID();
        leaseUntil = now.plus(PAYMENT_LEASE);
        paymentAttempts++;
        return PaymentClaim.granted(paymentLease);
    }

    /**
     * Decides the purchase with what the provider's answers prove, and says what to do with the
     * stock. Only the attempt that holds the current lease may decide: an attempt that took too
     * long and was replaced changes nothing ({@link StockAction#NONE}), even if its answer arrives.
     */
    public StockAction settle(UUID lease, PaymentEvidence evidence, Instant now) {
        if (!holdsLease(lease)) {
            return StockAction.NONE;
        }
        clearLease();
        return switch (evidence.outcome()) {
            case ACCEPTED -> {
                status = PurchaseStatus.PAID;
                providerRef = evidence.providerRef();
                yield StockAction.SELL;
            }
            case DECLINED -> {
                status = PurchaseStatus.DECLINED;
                yield StockAction.RELEASE;
            }
            case UNRECORDED_FAILURE -> {
                // "Nothing recorded" only speaks for these attempts. If an earlier one may have
                // charged, releasing the stock could sell copies somebody has paid for.
                if (earlierAttemptUnknown) {
                    yield reconcile(now);
                }
                status = PurchaseStatus.PAYMENT_FAILED;
                yield StockAction.RELEASE;
            }
            case UNKNOWN -> reconcile(now);
        };
    }

    /**
     * The provider answered, but the shop failed to record the result (the completion threw).
     * Money may have moved: the purchase waits for reconciliation, and the stock stays taken.
     */
    public void localFailure(UUID lease, Instant now) {
        if (holdsLease(lease)) {
            clearLease();
            reconcile(now);
        }
    }

    private StockAction reconcile(Instant now) {
        status = PurchaseStatus.RECONCILE;
        earlierAttemptUnknown = true;
        if (reviewAfter == null) {
            reviewAfter = createdAt.plus(REVIEW_AFTER);
        }
        return StockAction.RETAIN;
    }

    private boolean holdsLease(UUID lease) {
        return lease != null && lease.equals(paymentLease) && !status.isSettled();
    }

    private void clearLease() {
        paymentLease = null;
        leaseUntil = null;
    }

    // ---- The naive version -------------------------------------------------------------------

    /** Naive: any 201 means paid. */
    public void markPaid(String providerRef) {
        requirePending();
        this.status = PurchaseStatus.PAID;
        this.providerRef = Objects.requireNonNull(providerRef);
    }

    /** Naive: anything that is not a 201 (a timeout too) means failed. That is the bug. */
    public void markFailed() {
        requirePending();
        this.status = PurchaseStatus.PAYMENT_FAILED;
    }

    private void requirePending() {
        if (status != PurchaseStatus.PAYMENT_PENDING) {
            throw new IllegalStateException("Purchase " + id + " is " + status);
        }
    }

    // ---- Getters -----------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public Member getMember() {
        return member;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public List<PurchaseLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public List<String> getSkippedIsbns() {
        return Collections.unmodifiableList(skippedIsbns);
    }

    public long getTotalCents() {
        return totalCents;
    }

    public String getCurrency() {
        return currency;
    }

    public PurchaseStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public boolean isEarlierAttemptUnknown() {
        return earlierAttemptUnknown;
    }

    public Instant getReviewAfter() {
        return reviewAfter;
    }

    public int getPaymentAttempts() {
        return paymentAttempts;
    }
}
