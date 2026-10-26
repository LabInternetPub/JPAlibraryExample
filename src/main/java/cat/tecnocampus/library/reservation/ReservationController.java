package cat.tecnocampus.library.reservation;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/** Reserving copies, naive and with locks: 201 with Location, or 409 problem+json. */
@RestController
public class ReservationController {

    private final NaiveReservationService naive;
    private final LockingReservationService locking;
    private final ReservationLookupService lookup;

    public ReservationController(NaiveReservationService naive, LockingReservationService locking,
                                 ReservationLookupService lookup) {
        this.naive = naive;
        this.locking = locking;
        this.lookup = lookup;
    }

    @PostMapping("/naive/reservations")
    public ResponseEntity<ReservationResponse> naive(@Valid @RequestBody ReservationRequest request) {
        return created(naive.reserve(lookup.memberId(request.memberEmail()), copyIds(request)));
    }

    @PostMapping("/better/reservations")
    public ResponseEntity<ReservationResponse> locking(@Valid @RequestBody ReservationRequest request) {
        return created(locking.reserve(lookup.memberId(request.memberEmail()), copyIds(request)));
    }

    @GetMapping("/reservations/{id}")
    public ReservationResponse get(@PathVariable UUID id) {
        return lookup.get(id);
    }

    private List<UUID> copyIds(ReservationRequest request) {
        return lookup.copyIds(request.branch(), request.barcodes());
    }

    private static ResponseEntity<ReservationResponse> created(ReservationResponse reservation) {
        return ResponseEntity.created(URI.create("/reservations/" + reservation.id())).body(reservation);
    }
}
