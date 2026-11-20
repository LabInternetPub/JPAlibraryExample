package cat.tecnocampus.library.domain;

/**
 * Where the payment of a purchase is. A boolean "paid" is not enough: a purchase can also be
 * waiting for its payment, and a refused card and a failing provider are told apart.
 */
public enum PurchaseStatus {

    /** Created, stock taken, payment not decided yet (no attempt, or an attempt running now). */
    PAYMENT_PENDING,

    /** The provider charged the card and the answer was checked. Final. */
    PAID,

    /** The provider refused the payment. No money was taken. Final. */
    DECLINED,

    /** The provider failed (503) and recorded nothing. No money was taken. Final. */
    PAYMENT_FAILED;

    /** A settled purchase never changes again. */
    public boolean isSettled() {
        return this != PAYMENT_PENDING;
    }
}
