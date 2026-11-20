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
 * ({@link #settle}), and what to do when recording the answer fails ({@link #abandonAttempt}).
 * The services only lock the row, call these methods and apply the result.
 *
 * <p>There is no payment table: a purchase has at most one payment, and its state fits in a few
 * columns (status, provider reference, the lease of the attempt running now).
 */
@Entity
public class Purchase {

    public static final String CURRENCY = "EUR";

    /**
     * How long an attempt may take before another one is allowed to take over: far longer than the
     * provider normally takes. It is what lets a purchase move on when the process that was charging
     * it died while waiting for the provider.
     */
    public static final Duration PAYMENT_LEASE = Duration.ofSeconds(15);

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
     *       attempt never finished (its process died while waiting for the provider): the new
     *       attempt takes over, and charges with the same provider key.</li>
     * </ul>
     */
    public PaymentClaim claimPayment(Instant now) {
        if (status.isSettled()) {
            return PaymentClaim.settled();
        }
        if (paymentLease != null && now.isBefore(leaseUntil)) {
            return PaymentClaim.busy();
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
    public StockAction settle(UUID lease, PaymentEvidence evidence) {
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
            case FAILED -> {
                status = PurchaseStatus.PAYMENT_FAILED;
                yield StockAction.RELEASE;
            }
        };
    }

    /**
     * The provider answered, but the shop failed to record the result (the completion threw and
     * rolled back). The purchase stays {@code PAYMENT_PENDING} with its stock, and the lease is
     * given up at once, so a retry with the same key does not wait for it to run out: the retry
     * charges again with the same provider key, and the provider answers what it answered before.
     */
    public void abandonAttempt(UUID lease) {
        if (holdsLease(lease)) {
            clearLease();
        }
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

    /** Naive: the provider refused the card. */
    public void markDeclined() {
        requirePending();
        this.status = PurchaseStatus.DECLINED;
    }

    /** Naive: the provider failed. */
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

    public int getPaymentAttempts() {
        return paymentAttempts;
    }
}
