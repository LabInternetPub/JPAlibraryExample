package cat.tecnocampus.library.receipt;

import cat.tecnocampus.library.persistence.ReceiptMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Sends receipt messages, only ever from committed outbox rows. Two ways in:
 * <ul>
 *   <li><b>right after the commit</b> of the purchase ({@link #deliverAfterCommit(UUID)}), called by
 *       the purchase once its transaction has committed: the member gets the message at once. A
 *       purchase that rolled back never gets here, and its message row does not exist;</li>
 *   <li><b>later</b> ({@link #deliverPending}): every pending row, oldest first, in bounded pages.
 *       This is what the scheduled job runs, and what delivers a message whose application died
 *       between the commit and the send.</li>
 * </ul>
 */
@Component
public class ReceiptDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ReceiptDispatcher.class);
    static final int PAGE_SIZE = 100;

    private final ReceiptMessageRepository messages;
    private final ReceiptDelivery delivery;

    // Scenario and test switch: true stands for "the process dies right after each commit".
    private volatile boolean crashAfterCommit = false;

    public ReceiptDispatcher(ReceiptMessageRepository messages, ReceiptDelivery delivery) {
        this.messages = messages;
        this.delivery = delivery;
    }

    /** Sends the message of a purchase that has just committed. Must be called outside any transaction. */
    public void deliverAfterCommit(UUID messageId) {
        if (crashAfterCommit) {
            return;
        }
        try {
            delivery.deliver(messageId);
        } catch (RuntimeException e) {
            // The purchase has committed and the member gets their 201: the row stays pending and
            // the next run of the job sends it.
            log.warn("Receipt message {} left pending: {}", messageId, e.getMessage());
        }
    }

    /** Sends the pending messages, at most one page. Returns how many this run sent. */
    public int deliverPending() {
        List<UUID> pending = messages.findPendingIds(PageRequest.of(0, PAGE_SIZE));
        int sent = 0;
        for (UUID id : pending) {
            try {
                if (delivery.deliver(id)) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.warn("Receipt message {} left pending: {}", id, e.getMessage());
            }
        }
        return sent;
    }

    /** Scenarios and tests: {@code true} makes every purchase "die" between its commit and its message. */
    public void simulateCrashAfterCommit(boolean crashed) {
        this.crashAfterCommit = crashed;
    }
}
