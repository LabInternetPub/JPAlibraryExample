package cat.tecnocampus.library.reservation;

import cat.tecnocampus.library.domain.Reservation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(UUID id, Instant expiresAt, List<UUID> copyIds) {

    static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(reservation.getId(), reservation.getExpiresAt(), reservation.getCopyIds());
    }
}
