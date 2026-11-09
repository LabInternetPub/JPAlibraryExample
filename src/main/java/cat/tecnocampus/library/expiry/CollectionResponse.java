package cat.tecnocampus.library.expiry;

import cat.tecnocampus.library.domain.ReservationStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A collected reservation: one loan per copy, all due on the same day. */
public record CollectionResponse(UUID reservationId, ReservationStatus status, List<UUID> loanIds, Instant dueAt) {

    public CollectionResponse {
        loanIds = List.copyOf(loanIds);
    }
}
