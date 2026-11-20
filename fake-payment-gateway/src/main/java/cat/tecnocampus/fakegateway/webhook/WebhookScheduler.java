package cat.tecnocampus.fakegateway.webhook;

import cat.tecnocampus.fakegateway.charge.Charge;
import cat.tecnocampus.fakegateway.charge.Scenario;
import cat.tecnocampus.fakegateway.config.GatewayConfigStore.Settings;
import cat.tecnocampus.fakegateway.webhook.WebhookQueue.PendingDelivery;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Decides which notifications a charge owes and when. Only the webhook scenarios produce any:
 * a team that hasn't built UC-26 yet never receives an unexpected callback.
 */
@Component
public class WebhookScheduler {

    private static final Duration DUPLICATE_GAP = Duration.ofMillis(300);
    private static final Duration REORDER_GAP = Duration.ofMillis(400);

    private final WebhookQueue queue;

    public WebhookScheduler(WebhookQueue queue) {
        this.queue = queue;
    }

    public void scheduleFor(Charge charge, Scenario scenario, String callbackUrl, Settings settings) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return;
        }

        Instant now = Instant.now();
        Instant ordinary = now.plusMillis(settings.normalWebhookMillis());

        switch (scenario) {
            case PENDING_WEBHOOK -> enqueue(charge, "SUCCEEDED", 1, callbackUrl, ordinary);

            case LATE_WEBHOOK -> enqueue(charge, "SUCCEEDED", 1, callbackUrl,
                    now.plusMillis(settings.lateWebhookMillis()));

            // Same event identifier twice: the receiver has to deduplicate on it.
            case DUPLICATE_WEBHOOK -> {
                WebhookEvent event = event(charge, "SUCCEEDED", 1);
                queue.enqueue(new PendingDelivery(event, callbackUrl, ordinary, 1));
                queue.enqueue(new PendingDelivery(event, callbackUrl, ordinary.plus(DUPLICATE_GAP), 1));
            }

            // The newer notification lands first; the stale one has to be discarded by sequence.
            case OUT_OF_ORDER_WEBHOOK -> {
                enqueue(charge, "SUCCEEDED", 2, callbackUrl, ordinary);
                enqueue(charge, "PENDING", 1, callbackUrl, ordinary.plus(REORDER_GAP));
            }

            default -> {
                // SUCCESS, REJECTED, SERVER_ERROR and SLOW are synchronous-only.
            }
        }
    }

    private void enqueue(Charge charge, String status, int sequence, String callbackUrl, Instant deliverAt) {
        queue.enqueue(new PendingDelivery(event(charge, status, sequence), callbackUrl, deliverAt, 1));
    }

    private WebhookEvent event(Charge charge, String status, int sequence) {
        return new WebhookEvent(
                "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                sequence,
                charge.providerRef(),
                charge.reference(),
                status,
                charge.amountCents(),
                Instant.now());
    }
}
