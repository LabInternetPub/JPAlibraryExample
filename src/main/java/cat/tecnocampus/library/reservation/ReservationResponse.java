package cat.tecnocampus.library.reservation;

import cat.tecnocampus.library.domain.Reservation;
import cat.tecnocampus.library.domain.ReservationStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(UUID id, ReservationStatus status, Instant expiresAt, List<UUID> copyIds) {

    static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(reservation.getId(), reservation.getStatus(), reservation.getExpiresAt(),
                reservation.getCopyIds());
    }
}
