package cat.tecnocampus.library.shop;

import cat.tecnocampus.library.domain.PaymentEvidence;
import cat.tecnocampus.library.payment.PaymentGateway;
import cat.tecnocampus.library.payment.PaymentGateway.Charge;
import cat.tecnocampus.library.payment.PaymentGateway.Reply;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step 3 of the better purchase: the call to the provider, with no transaction at all.
 *
 * <p>{@code Propagation.NEVER} is a guard, not decoration: if a future change calls this method
 * from inside a transaction, Spring throws at once instead of silently bringing back the
 * connection held during the network call.
 *
 * <p>One call, no retry: a decline or a failure fails the purchase, and the member can start again.
 * The provider key is the purchase id, so a later attempt for the same purchase (a retry with the
 * same {@code Idempotency-Key}) can never be charged twice.
 */
@Service
public class PaymentCharges {

    private final PaymentGateway gateway;

    public PaymentCharges(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    @Transactional(propagation = Propagation.NEVER)
    public PaymentEvidence charge(PaymentAttempt attempt, String paymentToken) {
        Reply reply = gateway.charge(new Charge(attempt.amountCents(), attempt.currency(), paymentToken,
                attempt.purchaseId(), attempt.purchaseId().toString()));
        return switch (reply.outcome()) {
            case SUCCEEDED -> PaymentEvidence.accepted(reply.providerRef());
            case DECLINED -> PaymentEvidence.of(PaymentEvidence.Outcome.DECLINED);
            case FAILED -> PaymentEvidence.of(PaymentEvidence.Outcome.FAILED);
        };
    }
}
