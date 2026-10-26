package cat.tecnocampus.library.domain;

/** A different exception from {@link CopiesUnavailableException}, so a client can tell "limit" from "copy taken". */
public class MemberLimitExceededException extends RuntimeException {

    private final long existing;
    private final int requested;

    public MemberLimitExceededException(long existing, int requested) {
        super("A member can have at most " + MemberLimit.MAXIMUM + " active items: has "
                + existing + ", requested " + requested);
        this.existing = existing;
        this.requested = requested;
    }

    public long getExisting() {
        return existing;
    }

    public int getRequested() {
        return requested;
    }
}
