package cat.tecnocampus.library.shop;

import cat.tecnocampus.library.domain.PaymentEvidence;
import cat.tecnocampus.library.domain.ShopExceptions.PurchaseInProgressException;
import cat.tecnocampus.library.receipt.ReceiptDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * The better purchase. It has <b>no</b> {@code @Transactional}: it coordinates four steps, each a
 * call to another bean (so the transactional proxies apply), and the network call is between two
 * transactions, never inside one.
 *
 * <pre>
 * prepare (tx) ─► claim (tx) ─► charge (no tx, HTTP) ─► finish (tx) ─► send the receipt message (own tx)
 *                   │ busy: another request with the same key is charging → wait, claim again
 *                   └ settled: nothing to do → answer
 * </pre>
 *
 * <p>The receipt message is sent here, after {@code finish} has committed and returned its
 * connection, not from an {@code AFTER_COMMIT} listener: during "after commit" the finished
 * transaction's connection is still bound to the thread, and a listener that opens a new
 * transaction holds two connections at once. Eighty purchases finishing together then need 160
 * connections from a pool of 60, and they starve each other (measured: see the lesson).
 *
 * <p>The answer is read from the purchase at the end, so every request with the same key gets the
 * same status and body once the purchase is settled.
 */
@Service
public class PurchaseService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseService.class);

    /** How long a request waits for another request with the same key before answering 503. */
    static final Duration MAX_WAIT = Duration.ofSeconds(15);
    private static final Duration POLL = Duration.ofMillis(50);

    private final PurchasePreparation preparation;
    private final PaymentClaims claims;
    private final PaymentCharges charges;
    private final PaymentCompletion completion;
    private final PurchaseReads reads;
    private final ReceiptDispatcher dispatcher;

    public PurchaseService(PurchasePreparation preparation, PaymentClaims claims, PaymentCharges charges,
                           PaymentCompletion completion, PurchaseReads reads, ReceiptDispatcher dispatcher) {
        this.preparation = preparation;
        this.claims = claims;
        this.charges = charges;
        this.completion = completion;
        this.reads = reads;
        this.dispatcher = dispatcher;
    }

    public PurchaseAnswer purchase(PurchaseCommand command) {
        UUID purchaseId = prepare(command);
        long deadline = System.nanoTime() + MAX_WAIT.toNanos();
        while (true) {
            PaymentClaims.Claimed claimed = claims.claim(purchaseId);
            switch (claimed.kind()) {
                case SETTLED -> {
                    return reads.answer(purchaseId);
                }
                case BUSY -> {
                    if (System.nanoTime() > deadline) {
                        throw new PurchaseInProgressException(purchaseId);
                    }
                    sleep(POLL);
                }
                case GRANTED -> {
                    pay(claimed.attempt(), command.paymentToken());
                    return reads.answer(purchaseId);
                }
            }
        }
    }

    private UUID prepare(PurchaseCommand command) {
        try {
            return preparation.prepare(command);
        } catch (PurchasePreparation.KeyTakenException collision) {
            // Another request with the same key inserted it first and committed.
            return preparation.afterCollision(command);
        }
    }

    private void pay(PaymentAttempt attempt, String paymentToken) {
        PaymentEvidence evidence = charges.charge(attempt, paymentToken);
        PaymentCompletion.Completed completed;
        try {
            completed = completion.finish(attempt, evidence);
        } catch (RuntimeException e) {
            // The provider has answered, perhaps with "charged", and we could not record it. The
            // purchase stays PAYMENT_PENDING (202): a retry with the same key asks the provider
            // again with the same provider key and gets the same answer, without a second charge.
            log.warn("Recording the payment of purchase {} failed after the provider answered {}: {}",
                    attempt.purchaseId(), evidence.outcome(), e.toString());
            completion.abandon(attempt);
            return;
        }
        if (completed.receiptMessageId() != null) {
            dispatcher.deliverAfterCommit(completed.receiptMessageId());   // committed: safe to tell the member
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PurchaseInProgressException("interrupted");
        }
    }
}
