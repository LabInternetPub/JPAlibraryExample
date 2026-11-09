package cat.tecnocampus.library.expiry;

import cat.tecnocampus.library.reservation.ReservationLookupService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Collecting and cancelling a reservation, naive and better (option A). Expiry has no endpoint:
 * the sweep does it. 409 {@code reservation-not-active} once the deadline has passed or the
 * reservation has ended.
 */
@RestController
public class ExpiryController {

    private final NaiveExpiryTransitions naive;
    private final LockingExpiryTransitions locking;
    private final ReservationLookupService lookup;

    public ExpiryController(NaiveExpiryTransitions naive, LockingExpiryTransitions locking,
                            ReservationLookupService lookup) {
        this.naive = naive;
        this.locking = locking;
        this.lookup = lookup;
    }

    @PostMapping("/naive/reservations/{id}/collection")
    public CollectionResponse collect(@PathVariable UUID id, @Valid @RequestBody MemberRequest request) {
        return naive.collect(lookup.memberId(request.memberEmail()), id);
    }

    @DeleteMapping("/naive/reservations/{id}")
    public ResponseEntity<Void> cancel(@PathVariable UUID id, @RequestParam String memberEmail) {
        naive.cancel(lookup.memberId(memberEmail), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/better/reservations/{id}/collection")
    public CollectionResponse collectLocking(@PathVariable UUID id, @Valid @RequestBody MemberRequest request) {
        return locking.collect(lookup.memberId(request.memberEmail()), id);
    }

    @DeleteMapping("/better/reservations/{id}")
    public ResponseEntity<Void> cancelLocking(@PathVariable UUID id, @RequestParam String memberEmail) {
        locking.cancel(lookup.memberId(memberEmail), id);
        return ResponseEntity.noContent().build();
    }
}
