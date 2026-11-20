package cat.tecnocampus.library.domain;

import java.util.List;

/** The clean rejections of a purchase. Each one is answered with its own problem type. */
public final class ShopExceptions {

    private ShopExceptions() {
    }

    /** Malformed request: missing or too long key, empty or repeated ISBNs. Answered as 400. */
    public static class InvalidPurchaseException extends RuntimeException {

        public InvalidPurchaseException(String message) {
            super(message);
        }
    }

    /** None of the requested books is in stock: nothing is created, nothing is charged. 409. */
    public static class NothingAvailableException extends RuntimeException {

        private final List<String> isbns;

        public NothingAvailableException(List<String> isbns) {
            super("None of the requested books is in stock: " + isbns);
            this.isbns = List.copyOf(isbns);
        }

        public List<String> getIsbns() {
            return isbns;
        }
    }

    /** The key was already used with a different body. Not a retry: 422. */
    public static class IdempotencyKeyReusedException extends RuntimeException {

        public IdempotencyKeyReusedException(String key) {
            super("Idempotency key " + key + " was already used for a different purchase");
        }
    }

    /** Another request with the same key is still charging after the waiting time: 503, retry later. */
    public static class PurchaseInProgressException extends RuntimeException {

        public PurchaseInProgressException(Object purchaseId) {
            super("Purchase " + purchaseId + " is still being charged by another request; retry with the same key");
        }
    }
}
