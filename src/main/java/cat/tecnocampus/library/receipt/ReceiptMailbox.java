package cat.tecnocampus.library.receipt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands for the e-mail a member receives with their receipt: it prints the message on the
 * console and remembers it, so a scenario or a test can count what each member was told.
 * There is no mail server.
 *
 * <p>Like a real e-mail, a message that was sent cannot be taken back by a rollback.
 */
@Component
public class ReceiptMailbox {

    private static final Logger log = LoggerFactory.getLogger(ReceiptMailbox.class);

    /** One message as the member receives it. */
    public record Mail(UUID purchaseId, String receiptNumber, String memberEmail, String text) {
    }

    private final Map<UUID, List<Mail>> sent = new ConcurrentHashMap<>();

    public void send(Mail mail) {
        log.info("To {}: {}", mail.memberEmail(), mail.text());
        sent.computeIfAbsent(mail.purchaseId(), id -> new CopyOnWriteArrayList<>()).add(mail);
    }

    /** Messages sent about this purchase. Exactly one for a paid purchase is correct. */
    public int sentFor(UUID purchaseId) {
        List<Mail> mails = sent.get(purchaseId);
        return mails == null ? 0 : mails.size();
    }

    /** Messages sent to this member, about any purchase (including purchases that do not exist). */
    public List<Mail> sentTo(String memberEmail) {
        return sent.values().stream().flatMap(List::stream)
                .filter(mail -> mail.memberEmail().equals(memberEmail)).toList();
    }

    public int total() {
        return sent.values().stream().mapToInt(List::size).sum();
    }

    /** Scenarios and tests start from an empty mailbox. */
    public void clear() {
        sent.clear();
    }
}
