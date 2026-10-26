package cat.tecnocampus.library.reservation;

import cat.tecnocampus.library.domain.NotFoundException;
import cat.tecnocampus.library.persistence.BarcodeRef;
import cat.tecnocampus.library.persistence.CopyRepository;
import cat.tecnocampus.library.persistence.MemberRepository;
import cat.tecnocampus.library.persistence.ReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public ReservationLookupService(MemberRepository members, CopyRepository copies, ReservationRepository reservations) {
        this.members = members;
        this.copies = copies;
        this.reservations = reservations;
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

    @Transactional(readOnly = true)
    public ReservationResponse get(UUID id) {
        return reservations.findById(id).map(ReservationResponse::from)
                .orElseThrow(() -> new NotFoundException("Reservation", id));
    }
}
