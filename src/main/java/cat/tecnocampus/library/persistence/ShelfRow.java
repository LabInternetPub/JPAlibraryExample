package cat.tecnocampus.library.persistence;

import cat.tecnocampus.library.domain.CopyStatus;

import java.util.UUID;

/**
 * Exactly the values the shelf map needs. There is no field for the reservation or its deadline:
 * what is not selected cannot leak.
 */
public record ShelfRow(UUID copyId, String barcode, int floor, String shelf, String isbn, CopyStatus status) {

    /** Used by the JPQL constructor expression, whose CASE returns the status as text. */
    public ShelfRow(UUID copyId, String barcode, int floor, String shelf, String isbn, String status) {
        this(copyId, barcode, floor, shelf, isbn, CopyStatus.valueOf(status));
    }
}
