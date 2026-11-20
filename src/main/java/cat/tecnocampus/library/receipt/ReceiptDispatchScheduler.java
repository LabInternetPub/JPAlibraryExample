package cat.tecnocampus.library.receipt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every {@code library.receipts.dispatch-delay} ms, sends the receipt messages still pending:
 * those whose after-commit delivery never happened (the application died) or failed.
 *
 * <p>Off unless {@code library.receipts.dispatch-enabled=true}, like the expiry sweep: the
 * restart scenario must see a pending message before "the application comes back", and a job
 * running behind it would send it at a moment the scenario did not choose. Scenarios and tests
 * call {@link ReceiptDispatcher#deliverPending()} themselves. A production shop would leave it on.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "library.receipts.dispatch-enabled", havingValue = "true")
public class ReceiptDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReceiptDispatchScheduler.class);

    private final ReceiptDispatcher dispatcher;

    public ReceiptDispatchScheduler(ReceiptDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${library.receipts.dispatch-delay:2000}")
    public void dispatch() {
        int sent = dispatcher.deliverPending();
        if (sent > 0) {
            log.info("Receipt dispatcher sent {} pending message(s)", sent);
        }
    }
}
