package cat.tecnocampus.fakegateway.webhook;

import java.time.Instant;

/**
 * The notification body. Two fields carry the whole lesson of UC-26: {@code eventId}, which is
 * what the receiver deduplicates on, and {@code sequence}, which is what lets it discard a
 * notification that arrived after a newer one.
 */
public record WebhookEvent(
        String eventId,
        int sequence,
        String providerRef,
        String reference,
        String status,
        long amountCents,
        Instant occurredAt) {
}
