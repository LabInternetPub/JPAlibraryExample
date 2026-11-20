package cat.tecnocampus.library.shop;

import cat.tecnocampus.library.domain.NotFoundException;
import cat.tecnocampus.library.domain.PaymentEvidence;
import cat.tecnocampus.library.domain.Purchase;
import cat.tecnocampus.library.domain.Receipt;
import cat.tecnocampus.library.domain.ReceiptMessage;
import cat.tecnocampus.library.domain.StockAction;
import cat.tecnocampus.library.persistence.PurchaseRepository;
import cat.tecnocampus.library.scenario.TransitionPauses;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Step 4 of the better purchase: record what the provider's answer proves. A short transaction
 * with the purchase locked, then the offers (always in that order).
 *
 * <p>On {@code PAID}, the receipt and its outbox message are written in this same transaction:
 * the purchase, the receipt and the message commit together, or none of them does. The message is
 * sent only after the commit, by the coordinator ({@link PurchaseService}), once this transaction
 * has given its connection back.
 */
@Service
public class PaymentCompletion {

    private final PurchaseRepository purchases;
    private final ShopStock stock;
    private final EntityManager entityManager;
    private final TransitionPauses pauses;
    private final Clock clock;

    public PaymentCompletion(PurchaseRepository purchases, ShopStock stock, EntityManager entityManager,
                             TransitionPauses pauses, Clock clock) {
        this.purchases = purchases;
        this.stock = stock;
        this.entityManager = entityManager;
        this.pauses = pauses;
        this.clock = clock;
    }

    /**
     * What the completion did.
     *
     * @param receiptMessageId the outbox message to send once committed; only when the purchase was paid
     */
    public record Completed(StockAction action, UUID receiptMessageId) {
    }

    @Transactional(timeout = 5)
    public Completed finish(PaymentAttempt attempt, PaymentEvidence evidence) {
        Purchase purchase = lock(attempt.purchaseId());
        StockAction action = purchase.settle(attempt.lease(), evidence);
        UUID messageId = null;
        switch (action) {
            case SELL -> messageId = issueReceipt(purchase, clock.instant());
            case RELEASE -> stock.release(purchase);
            case NONE -> {
                // This attempt lost its lease; the one that holds it decides.
            }
        }
        return new Completed(action, messageId);
    }

    /**
     * The finish threw and rolled back after the provider answered: nothing was recorded, the
     * purchase is still {@code PAYMENT_PENDING} with its copies. Give up the lease, so a retry with
     * the same key can charge again at once (same provider key: no second charge).
     */
    @Transactional(timeout = 5)
    public void abandon(PaymentAttempt attempt) {
        lock(attempt.purchaseId()).abandonAttempt(attempt.lease());
    }

    private UUID issueReceipt(Purchase purchase, Instant now) {
        Receipt receipt = Receipt.issueFor(purchase, now);
        entityManager.persist(receipt);
        ReceiptMessage message = ReceiptMessage.confirming(receipt, now);
        entityManager.persist(message);
        pauses.observe(TransitionPauses.RECEIPT_ISSUED, purchase.getMember().getId());  // scenarios: "something fails here"
        return message.getId();
    }

    private Purchase lock(UUID purchaseId) {
        return purchases.lockById(purchaseId).orElseThrow(() -> new NotFoundException("Purchase", purchaseId));
    }
}
