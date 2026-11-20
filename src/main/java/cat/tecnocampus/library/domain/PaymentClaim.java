package cat.tecnocampus.library.domain;

import java.util.UUID;

/**
 * The answer to "may I charge this purchase now?".
 *
 * @param kind  granted, busy (another attempt holds the lease) or settled (nothing left to do)
 * @param lease the nonce that identifies this attempt; only when granted
 */
public record PaymentClaim(Kind kind, UUID lease) {

    public enum Kind { GRANTED, BUSY, SETTLED }

    static PaymentClaim granted(UUID lease) {
        return new PaymentClaim(Kind.GRANTED, lease);
    }

    static PaymentClaim busy() {
        return new PaymentClaim(Kind.BUSY, null);
    }

    static PaymentClaim settled() {
        return new PaymentClaim(Kind.SETTLED, null);
    }
}
