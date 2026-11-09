package cat.tecnocampus.library.reservation;

import cat.tecnocampus.library.domain.NotFoundException;
import cat.tecnocampus.library.domain.Reservation;
import cat.tecnocampus.library.domain.ReservationExpiredException;
import cat.tecnocampus.library.persistence.BarcodeRef;
import cat.tecnocampus.library.persistence.CopyRepository;
import cat.tecnocampus.library.persistence.MemberRepository;
import cat.tecnocampus.library.persistence.ReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Turns the business keys of a request into ids, and reads a reservation back. */
@Service
public class ReservationLookupService {

    private final MemberRepository members;
    private final CopyRepository copies;
    private final ReservationRepository reservations;
    private final Clock clock;

    public ReservationLookupService(MemberRepository members, CopyRepository copies, ReservationRepository reservations,
                                    Clock clock) {
        this.members = members;
        this.copies = copies;
        this.reservations = reservations;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UUID memberId(String email) {
        return members.findByEmail(email).orElseThrow(() -> new NotFoundException("Member", email)).getId();
    }

    /** The ids in the order of the barcodes. */
    @Transactional(readOnly = true)
    public List<UUID> copyIds(String branch, List<String> barcodes) {
        Map<String, UUID> ids = copies.findIdsByBranchAndBarcodes(branch, barcodes).stream()
                .collect(Collectors.toMap(BarcodeRef::barcode, BarcodeRef::id));
        return barcodes.stream()
                .map(barcode -> {
                    UUID id = ids.get(barcode);
                    if (id == null) {
                        throw new NotFoundException("Copy", branch + "/" + barcode);
                    }
                    return id;
                })
                .toList();
    }

    /**
     * 410 Gone as soon as the deadline has passed, even if the row still says {@code ACTIVE}:
     * lazy expiry on read. Nothing is written; storing the expiry is the sweep's job.
     */
    @Transactional(readOnly = true)
    public ReservationResponse get(UUID id) {
        Reservation reservation = reservations.findById(id)
                .orElseThrow(() -> new NotFoundException("Reservation", id));
        if (reservation.isExpiredAt(clock.instant())) {
            throw new ReservationExpiredException(id);
        }
        return ReservationResponse.from(reservation);
    }
}
