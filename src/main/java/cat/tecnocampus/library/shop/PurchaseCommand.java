package cat.tecnocampus.library.shop;

import cat.tecnocampus.library.domain.ShopExceptions.InvalidPurchaseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/**
 * One purchase request: who buys, which books, how they pay, and the key that makes a retry
 * recognisable.
 *
 * @param idempotencyKey chosen by the client, the same on every retry of this purchase
 * @param isbns          the books, one copy each, no repeats
 * @param paymentToken   forwarded to the provider, never stored
 */
public record PurchaseCommand(String memberEmail, String idempotencyKey, List<String> isbns, String paymentToken) {

    static final int MAX_KEY_LENGTH = 100;

    public PurchaseCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new InvalidPurchaseException("Idempotency-Key must have between 1 and " + MAX_KEY_LENGTH + " characters");
        }
        if (isbns == null || isbns.isEmpty()) {
            throw new InvalidPurchaseException("A purchase names at least one book");
        }
        if (new HashSet<>(isbns).size() != isbns.size()) {
            throw new InvalidPurchaseException("Each book is bought once: an ISBN is repeated");
        }
        if (memberEmail == null || memberEmail.isBlank() || paymentToken == null || paymentToken.isBlank()) {
            throw new InvalidPurchaseException("memberEmail and paymentToken are required");
        }
        isbns = List.copyOf(isbns);
    }

    /**
     * A hash of what the request asks for. Two requests with the same key are the same purchase only
     * if they ask for the same books with the same token; the order of the ISBNs does not matter.
     */
    public String fingerprint() {
        String canonical = String.join(",", isbns.stream().sorted().toList()) + "|" + paymentToken;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
