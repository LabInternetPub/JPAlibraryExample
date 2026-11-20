package cat.tecnocampus.library.shop;

import cat.tecnocampus.library.domain.PaymentEvidence;
import cat.tecnocampus.library.payment.PaymentGateway;
import cat.tecnocampus.library.payment.PaymentGateway.Charge;
import cat.tecnocampus.library.payment.PaymentGateway.Reply;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Step 3 of the better purchase: the call to the provider, with no transaction at all.
 *
 * <p>{@code Propagation.NEVER} is a guard, not decoration: if a future change calls this method
 * from inside a transaction, Spring throws at once instead of silently bringing back the
 * connection held during the network call.
 *
 * <p>Retries: only a 503 (the provider promises it recorded nothing), at most {@value #MAX_CALLS}
 * calls, always with the same provider key (the purchase id). A timeout is never retried here: it
 * is evidence of nothing, and the next request with the same key will ask again anyway.
 */
@Service
public class PaymentCharges {

    static final int MAX_CALLS = 3;
    private static final Duration BACKOFF = Duration.ofMillis(50);

    private final PaymentGateway gateway;

    public PaymentCharges(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    @Transactional(propagation = Propagation.NEVER)
    public PaymentEvidence charge(PaymentAttempt attempt, String paymentToken) {
        Charge charge = new Charge(attempt.amountCents(), attempt.currency(), paymentToken,
                attempt.purchaseId(), attempt.purchaseId().toString());
        for (int call = 1; ; call++) {
            Reply reply = gateway.charge(charge);
            switch (reply.outcome()) {
                case SUCCEEDED:
                    return PaymentEvidence.accepted(reply.providerRef());
                case DECLINED:
                    return PaymentEvidence.of(PaymentEvidence.Outcome.DECLINED);
                case UNKNOWN:
                    return PaymentEvidence.of(PaymentEvidence.Outcome.UNKNOWN);
                case RETRYABLE:
                    if (call == MAX_CALLS) {
                        return PaymentEvidence.of(PaymentEvidence.Outcome.UNRECORDED_FAILURE);
                    }
                    sleep(BACKOFF.multipliedBy(call));
                    break;
            }
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
