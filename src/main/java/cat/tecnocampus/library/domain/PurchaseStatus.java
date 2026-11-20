package cat.tecnocampus.library.domain;

/**
 * What the shop knows about the money of a purchase. A boolean "paid" cannot say "we do not know",
 * and "we do not know" is the state that must never be treated as "no".
 */
public enum PurchaseStatus {

    /** Created, stock taken, payment not decided yet (no attempt, or an attempt running now). */
    PAYMENT_PENDING,

    /** The provider charged the card and the answer was checked. Final. */
    PAID,

    /** The provider refused the payment. No money was taken. Final. */
    DECLINED,

    /** The provider failed on every attempt and recorded nothing. No money was taken. Final. */
    PAYMENT_FAILED,

    /** The shop does not know whether money was taken. Stock kept; a retry or a person settles it. */
    RECONCILE;

    /** A settled purchase never changes again. */
    public boolean isSettled() {
        return this == PAID || this == DECLINED || this == PAYMENT_FAILED;
    }
}
