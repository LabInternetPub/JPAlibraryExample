package cat.tecnocampus.library.shop;

import cat.tecnocampus.library.domain.Purchase;
import cat.tecnocampus.library.domain.PurchaseStatus;
import cat.tecnocampus.library.domain.Receipt;

/**
 * The HTTP answer to a purchase request: a status code and the purchase.
 *
 * <p>The code follows from the purchase's status, and a settled purchase never changes. So a retry
 * with the same key, which reads the same purchase, gets the same code and the same body as the
 * first request: the idempotent answer needs no stored copy of the response.
 *
 * <table>
 *   <tr><th>Purchase</th><th>Answer</th><th>For the client</th></tr>
 *   <tr><td>PAID</td><td>201</td><td>bought, here is the receipt</td></tr>
 *   <tr><td>RECONCILE, PAYMENT_PENDING</td><td>202</td><td>not decided yet: ask again with the same key, or look at the purchase later</td></tr>
 *   <tr><td>DECLINED</td><td>402</td><td>the card was refused: use another one (with a new key)</td></tr>
 *   <tr><td>PAYMENT_FAILED</td><td>503</td><td>the provider is failing, nothing was charged: try later (with a new key)</td></tr>
 * </table>
 */
public record PurchaseAnswer(int httpStatus, PurchaseResponse body) {

    static PurchaseAnswer of(Purchase purchase, Receipt receipt) {
        return new PurchaseAnswer(statusFor(purchase.getStatus()), PurchaseResponse.of(purchase, receipt));
    }

    static int statusFor(PurchaseStatus status) {
        return switch (status) {
            case PAID -> 201;
            case RECONCILE, PAYMENT_PENDING -> 202;
            case DECLINED -> 402;
            case PAYMENT_FAILED -> 503;
        };
    }
}
