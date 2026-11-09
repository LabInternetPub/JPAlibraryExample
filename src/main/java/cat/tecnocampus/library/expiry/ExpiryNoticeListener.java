package cat.tecnocampus.library.expiry;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends an expiry notice only once the expiry has committed. The better transitions publish the
 * notice as an event inside their transaction; if the transaction rolls back, the event is
 * discarded and the member is never told about an expiry that did not happen.
 *
 * <p>The trade-off: if the application dies between the commit and the send, the notice is lost.
 * "At most once" here; "exactly once" would need the notice stored in the same transaction (an
 * outbox table) and sent by a separate job.
 */
@Component
class ExpiryNoticeListener {

    private final ExpiryNotices notices;

    ExpiryNoticeListener(ExpiryNotices notices) {
        this.notices = notices;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void expired(ExpiryNotice notice) {
        notices.send(notice);
    }
}
