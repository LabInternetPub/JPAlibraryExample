package cat.tecnocampus.library.persistence;

import java.util.UUID;

/**
 * The scalars a transition needs before it takes any lock: which reservation, whose. A projection,
 * not an entity: it leaves the persistence context empty, so the Reservation loaded afterwards,
 * under the lock, is read fresh from the database (the stale-cancellation lesson).
 */
public record ReservationKey(UUID reservationId, UUID memberId) {
}
