package cat.tecnocampus.library.domain;

import java.util.UUID;

/**
 * Reading a reservation whose deadline has passed, whether or not the sweep has stored it as
 * expired yet. Answered as 410 Gone: the holder sees it as expired at once.
 */
public class ReservationExpiredException extends RuntimeException {

    private final UUID reservationId;

    public ReservationExpiredException(UUID reservationId) {
        super("The reservation has expired: " + reservationId);
        this.reservationId = reservationId;
    }

    public UUID getReservationId() {
        return reservationId;
    }
}
