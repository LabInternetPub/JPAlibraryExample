package cat.tecnocampus.library.receipt;

import cat.tecnocampus.library.domain.ReceiptMessage;
import cat.tecnocampus.library.persistence.ReceiptMessageRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends one pending message and marks it sent, in a transaction of its own.
 *
 * <p>The row is locked with {@code SKIP LOCKED}: whoever gets it sends it; anyone else arriving
 * at the same time finds nothing and does nothing. Once marked {@code SENT} and committed, nobody
 * sends it again.
 *
 * <p>The one window left: the application dies after {@code send} and before the commit. The row
 * is still {@code PENDING}, so the next run sends it again. The guarantee is <b>at least once</b>:
 * a member may (rarely) get the same message twice, but never zero times, and never a message
 * about a receipt that does not exist. Exactly once is impossible when the effect leaves the
 * system and the database cannot commit together with it.
 */
@Component
public class ReceiptDelivery {

    private final ReceiptMessageRepository messages;
    private final ReceiptMailbox mailbox;
    private final Clock clock;

    public ReceiptDelivery(ReceiptMessageRepository messages, ReceiptMailbox mailbox, Clock clock) {
        this.messages = messages;
        this.mailbox = mailbox;
        this.clock = clock;
    }

    /** {@code REQUIRES_NEW}: it runs after the purchase has committed, in a transaction of its own. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean deliver(UUID messageId) {
        Optional<ReceiptMessage> pending = messages.lockIfPending(messageId);
        if (pending.isEmpty()) {
            return false;               // already sent, or someone else is sending it right now
        }
        ReceiptMessage message = pending.get();
        mailbox.send(new ReceiptMailbox.Mail(message.getPurchaseId(), message.getReceiptNumber(),
                message.getMemberEmail(), message.getText()));
        message.markSent(clock.instant());
        return true;
    }
}
