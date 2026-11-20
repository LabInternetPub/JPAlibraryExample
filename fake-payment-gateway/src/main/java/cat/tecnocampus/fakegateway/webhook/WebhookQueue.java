package cat.tecnocampus.fakegateway.webhook;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * Deliveries waiting for their moment, plus the log of what was actually sent. This is the
 * part a stub server can't express: the gateway has to remember what it owes and to whom, and
 * decide later, on a clock, that the time has come.
 */
@Component
public class WebhookQueue {

    public record PendingDelivery(WebhookEvent event, String callbackUrl, Instant deliverAt, int attempt) {

        PendingDelivery retryAt(Instant next) {
            return new PendingDelivery(event, callbackUrl, next, attempt + 1);
        }
    }

    public record DeliveryAttempt(
            String eventId,
            int sequence,
            String callbackUrl,
            Instant at,
            int attempt,
            boolean delivered,
            String error) {
    }

    private final ConcurrentLinkedQueue<PendingDelivery> pending = new ConcurrentLinkedQueue<>();
    private final CopyOnWriteArrayList<DeliveryAttempt> log = new CopyOnWriteArrayList<>();

    public void enqueue(PendingDelivery delivery) {
        pending.add(delivery);
    }

    /** Everything due by {@code now}, oldest scheduled moment first, removed from the queue. */
    public List<PendingDelivery> pollDue(Instant now) {
        List<PendingDelivery> due = new ArrayList<>();
        for (PendingDelivery delivery : pending) {
            if (!delivery.deliverAt().isAfter(now) && pending.remove(delivery)) {
                due.add(delivery);
            }
        }
        due.sort(Comparator.comparing(PendingDelivery::deliverAt));
        return due;
    }

    public void record(DeliveryAttempt attempt) {
        log.add(attempt);
    }

    public List<DeliveryAttempt> attempts() {
        return List.copyOf(log);
    }

    public int pendingCount() {
        return pending.size();
    }

    public void clear() {
        pending.clear();
        log.clear();
    }
}
