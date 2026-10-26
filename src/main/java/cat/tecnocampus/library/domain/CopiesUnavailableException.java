package cat.tecnocampus.library.domain;

import java.util.List;
import java.util.UUID;

/**
 * Runtime exception on purpose: thrown inside a transaction, it makes Spring roll back
 * everything the request had already done, so a rejected reservation leaves no trace.
 */
public class CopiesUnavailableException extends RuntimeException {

    private final List<UUID> copyIds;

    public CopiesUnavailableException(List<UUID> copyIds) {
        super("Copies not available: " + copyIds);
        this.copyIds = List.copyOf(copyIds);
    }

    public List<UUID> getCopyIds() {
        return copyIds;
    }
}
