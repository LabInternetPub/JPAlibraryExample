package cat.tecnocampus.library.shelfmap;

import cat.tecnocampus.library.domain.Copy;
import cat.tecnocampus.library.domain.CopyStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One copy in the naive shelf map. A DTO, not the entity: the reservation id and its deadline
 * stay out because this record has no field for them.
 */
public record CopyItem(UUID id, String barcode, int floor, String shelf, String isbn, CopyStatus status) {

    static CopyItem of(Copy copy, Instant now) {
        return new CopyItem(copy.getId(), copy.getBarcode(), copy.getFloor(), copy.getShelf(),
                copy.getBook().getIsbn(), copy.visibleStatusAt(now));
    }
}
