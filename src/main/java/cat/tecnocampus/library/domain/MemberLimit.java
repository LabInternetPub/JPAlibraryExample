package cat.tecnocampus.library.domain;

/**
 * At most five active items per member: open loans plus copies in live reservations.
 *
 * <p>The rule is correct for one request. It cannot protect itself against two simultaneous
 * requests of the same member: both may count the same "existing" before either commits
 * (write skew). Protecting it is the job of the transaction that calls it.
 */
public final class MemberLimit {

    public static final int MAXIMUM = 5;

    private MemberLimit() {
    }

    public static void check(long existing, int requested) {
        if (requested <= 0) {
            throw new IllegalArgumentException("A request must ask for at least one item");
        }
        if (existing + requested > MAXIMUM) {
            throw new MemberLimitExceededException(existing, requested);
        }
    }
}
