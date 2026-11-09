package cat.tecnocampus.library.domain;

import java.util.UUID;

/**
 * The reservation can no longer be collected or cancelled: it has expired (its deadline has
 * passed, even if nothing has stored it as expired yet), or it was already collected, cancelled
 * or expired. Runtime exception, so the transaction that throws it rolls back. Answered as 409.
 */
public class ReservationNotActiveException extends RuntimeException {

    private final UUID reservationId;

    public ReservationNotActiveException(UUID reservationId) {
        super("The reservation is not active: " + reservationId);
        this.reservationId = reservationId;
    }

    public UUID getReservationId() {
        return reservationId;
    }
}
