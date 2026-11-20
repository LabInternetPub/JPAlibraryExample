package cat.tecnocampus.fakegateway.charge;

/**
 * Behaviour is selected by the payment token, the way real providers select it by test card
 * number. The application under test stays unaware that any of this exists: it just forwards
 * whatever token it was given.
 */
public enum Scenario {

    /** Charge succeeds immediately. Also the behaviour for any unrecognised token. */
    SUCCESS("tok_ok"),

    /** Card declined: a definitive `402`, not something to retry. */
    REJECTED("tok_reject"),

    /** Gateway blows up with a `503`: retryable, and nothing is recorded. */
    SERVER_ERROR("tok_fail"),

    /** Correct answer, but so late the caller has probably given up already. */
    SLOW("tok_slow"),

    /** Synchronous `202 PENDING`; the outcome arrives only by webhook. */
    PENDING_WEBHOOK("tok_pending"),

    /** Synchronous `202 PENDING`; the webhook lands after the hold has expired. */
    LATE_WEBHOOK("tok_late"),

    /** Succeeds, then delivers the very same event twice. */
    DUPLICATE_WEBHOOK("tok_duplicate"),

    /** Succeeds, then delivers sequence 2 before sequence 1. */
    OUT_OF_ORDER_WEBHOOK("tok_out_of_order");

    private final String token;

    Scenario(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public static Scenario fromToken(String token) {
        if (token == null) {
            return SUCCESS;
        }
        String normalized = token.trim();
        for (Scenario scenario : values()) {
            if (scenario.token.equalsIgnoreCase(normalized)) {
                return scenario;
            }
        }
        return SUCCESS;
    }
}
