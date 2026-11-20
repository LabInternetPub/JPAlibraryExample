package cat.tecnocampus.library.domain;

import java.util.Objects;

/**
 * What the payment provider's answers prove about the money, after the retries. The domain
 * decides with this, never with an HTTP status or an exception.
 *
 * @param outcome     what is proven
 * @param providerRef the provider's reference of the charge, only when it was accepted
 */
public record PaymentEvidence(Outcome outcome, String providerRef) {

    public enum Outcome {
        /** A 201 whose amount, currency and reference match the purchase: money was taken. */
        ACCEPTED,
        /** A 402: the provider refused. No money was taken, and asking again will not change it. */
        DECLINED,
        /** A 503 on every attempt: the provider says it recorded nothing. */
        UNRECORDED_FAILURE,
        /** No usable answer (timeout, 202, malformed or mismatching body): money may have been taken. */
        UNKNOWN
    }

    public PaymentEvidence {
        Objects.requireNonNull(outcome);
        if (outcome == Outcome.ACCEPTED && (providerRef == null || providerRef.isBlank())) {
            throw new IllegalArgumentException("An accepted charge has a provider reference");
        }
    }

    public static PaymentEvidence accepted(String providerRef) {
        return new PaymentEvidence(Outcome.ACCEPTED, providerRef);
    }

    public static PaymentEvidence of(Outcome outcome) {
        return new PaymentEvidence(outcome, null);
    }
}
