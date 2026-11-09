package cat.tecnocampus.library.expiry;

import java.util.List;
import java.util.UUID;

/** What the member is told when a reservation expires: who, and which copies. */
public record ExpiryNotice(UUID reservationId, String memberEmail, List<String> barcodes) {

    public ExpiryNotice {
        barcodes = List.copyOf(barcodes);
    }
}
