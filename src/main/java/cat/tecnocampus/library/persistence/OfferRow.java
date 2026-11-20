package cat.tecnocampus.library.persistence;

import java.util.UUID;

/** A book for sale, as the purchase needs it: no entity, nothing lazy. */
public record OfferRow(UUID offerId, String isbn, String title, long priceCents) {
}
