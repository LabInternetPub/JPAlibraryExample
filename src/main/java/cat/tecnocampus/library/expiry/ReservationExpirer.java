package cat.tecnocampus.library.expiry;

import java.util.UUID;

/**
 * Expires one reservation in its own transaction. The sweep depends on this interface, so the
 * same sweep can run with the naive or the better transitions.
 */
public interface ReservationExpirer {

    /** @return true only if this call expired the reservation (and released its copies) */
    boolean expire(UUID reservationId);
}
