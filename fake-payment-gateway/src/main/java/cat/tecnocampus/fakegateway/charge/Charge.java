package cat.tecnocampus.fakegateway.charge;

import java.time.Instant;

public record Charge(
        String providerRef,
        String reference,
        String idempotencyKey,
        long amountCents,
        String currency,
        Status status,
        String declineReason,
        Scenario scenario,
        Instant createdAt) {

    public enum Status {
        SUCCEEDED,
        REJECTED,
        PENDING
    }
}
