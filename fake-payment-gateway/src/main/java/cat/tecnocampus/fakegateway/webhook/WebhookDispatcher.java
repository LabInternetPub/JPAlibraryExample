package cat.tecnocampus.fakegateway.webhook;

import cat.tecnocampus.fakegateway.webhook.WebhookQueue.DeliveryAttempt;
import cat.tecnocampus.fakegateway.webhook.WebhookQueue.PendingDelivery;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Pushes due webhooks into the application under test. */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(2);

    private final WebhookQueue queue;
    private final WebhookSigner signer;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public WebhookDispatcher(WebhookQueue queue, WebhookSigner signer, ObjectMapper objectMapper) {
        this.queue = queue;
        this.signer = signer;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Scheduled(fixedDelay = 200)
    public void deliverDue() {
        for (PendingDelivery delivery : queue.pollDue(Instant.now())) {
            deliver(delivery);
        }
    }

    private void deliver(PendingDelivery delivery) {
        WebhookEvent event = delivery.event();
        try {
            String rawBody = objectMapper.writeValueAsString(event);
            restClient.post()
                    .uri(delivery.callbackUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Gateway-Signature", signer.sign(rawBody))
                    .header("X-Gateway-Event-Id", event.eventId())
                    .body(rawBody)
                    .retrieve()
                    .toBodilessEntity();

            queue.record(new DeliveryAttempt(event.eventId(), event.sequence(), delivery.callbackUrl(),
                    Instant.now(), delivery.attempt(), true, null));
        } catch (Exception e) {
            queue.record(new DeliveryAttempt(event.eventId(), event.sequence(), delivery.callbackUrl(),
                    Instant.now(), delivery.attempt(), false, e.getMessage()));

            if (delivery.attempt() < MAX_ATTEMPTS) {
                queue.enqueue(delivery.retryAt(Instant.now().plus(RETRY_BACKOFF)));
            } else {
                log.warn("Giving up on webhook {} after {} attempts: {}",
                        event.eventId(), delivery.attempt(), e.getMessage());
            }
        }
    }
}
