package cat.tecnocampus.library.shop;

import cat.tecnocampus.library.domain.NotFoundException;
import cat.tecnocampus.library.domain.PaymentClaim;
import cat.tecnocampus.library.domain.Purchase;
import cat.tecnocampus.library.persistence.PurchaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Step 2 of the better purchase: "may this request charge now?". A short transaction with the
 * purchase row locked, so two requests with the same key cannot both get the right. The lock ends
 * at the commit, before anybody calls the provider.
 */
@Service
public class PaymentClaims {

    /** The claim, and the attempt to run when it was granted. */
    public record Claimed(PaymentClaim.Kind kind, PaymentAttempt attempt) {
    }

    private final PurchaseRepository purchases;
    private final Clock clock;

    public PaymentClaims(PurchaseRepository purchases, Clock clock) {
        this.purchases = purchases;
        this.clock = clock;
    }

    @Transactional(timeout = 5)
    public Claimed claim(UUID purchaseId) {
        Purchase purchase = purchases.lockById(purchaseId)
                .orElseThrow(() -> new NotFoundException("Purchase", purchaseId));
        PaymentClaim claim = purchase.claimPayment(clock.instant());
        PaymentAttempt attempt = claim.kind() == PaymentClaim.Kind.GRANTED
                ? new PaymentAttempt(purchase.getId(), claim.lease(), purchase.getTotalCents(), purchase.getCurrency())
                : null;
        return new Claimed(claim.kind(), attempt);
    }
}
