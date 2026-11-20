package cat.tecnocampus.library.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;
import java.util.UUID;

/**
 * One book bought. ISBN, title and price are copied from the offer when the purchase is made
 * ("frozen"): a later change of price must not change what a member paid or what the receipt says.
 */
@Embeddable
public class PurchaseLine {

    @Column(nullable = false)
    private UUID offerId;

    @Column(nullable = false)
    private String isbn;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private long priceCents;

    protected PurchaseLine() {
    }

    public PurchaseLine(UUID offerId, String isbn, String title, long priceCents) {
        this.offerId = Objects.requireNonNull(offerId);
        this.isbn = Objects.requireNonNull(isbn);
        this.title = Objects.requireNonNull(title);
        this.priceCents = priceCents;
    }

    public UUID getOfferId() {
        return offerId;
    }

    public String getIsbn() {
        return isbn;
    }

    public String getTitle() {
        return title;
    }

    public long getPriceCents() {
        return priceCents;
    }
}
