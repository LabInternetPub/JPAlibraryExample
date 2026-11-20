package cat.tecnocampus.library.shop;

import java.util.UUID;

/**
 * Everything the charge step needs, as plain values. No entity crosses into the network step, so
 * nothing lazy can send a query (and take a connection) while waiting for the provider.
 *
 * @param lease the nonce that identifies this attempt; only its holder may record the result
 */
public record PaymentAttempt(UUID purchaseId, UUID lease, long amountCents, String currency) {
}
