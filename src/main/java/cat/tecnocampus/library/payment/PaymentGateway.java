package cat.tecnocampus.library.payment;

import java.util.Objects;
import java.util.UUID;

/**
 * The external payment provider, as the shop sees it. One call, one {@link Reply}: the adapter
 * turns statuses, bodies and exceptions into three outcomes, and never throws.
 */
public interface PaymentGateway {

    Reply charge(Charge charge);

    /**
     * @param reference      the purchase id; the provider echoes it back, and the answer is accepted
     *                       only if it matches
     * @param idempotencyKey sent as the {@code Idempotency-Key} header. The same key on every attempt
     *                       of the same purchase (the purchase id): the provider then charges at
     *                       most once, and answers a repeated key with its stored answer
     */
    record Charge(long amountCents, String currency, String paymentToken, UUID reference, String idempotencyKey) {

        public Charge {
            if (amountCents <= 0) {
                throw new IllegalArgumentException("Only positive amounts reach the provider");
            }
            Objects.requireNonNull(currency);
            Objects.requireNonNull(paymentToken);
            Objects.requireNonNull(reference);
            Objects.requireNonNull(idempotencyKey);
        }
    }

    enum Outcome {
        /** 201 with the same amount, currency and reference, and a provider reference. */
        SUCCEEDED,
        /** 402: refused. */
        DECLINED,
        /**
         * Anything else: a 503 (the provider failed and recorded nothing), or no usable answer. The
         * purchase fails; the shop does not retry.
         */
        FAILED
    }

    /**
     * @param detail what was observed, for logs and scenario reports (a status, an exception class)
     */
    record Reply(Outcome outcome, String providerRef, String detail) {
    }
}
