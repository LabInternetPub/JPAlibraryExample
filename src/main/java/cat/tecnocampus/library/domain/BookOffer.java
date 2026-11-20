package cat.tecnocampus.library.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;

import java.util.Objects;
import java.util.UUID;

/**
 * A book the shop sells: its price and how many copies are left to sell (the stock).
 *
 * <p>The copies for sale are not {@link Copy} rows: they are not lent, have no barcode and nobody
 * cares which one a member receives. A counter is enough, like Affora's general admission.
 *
 * <p>"The stock never goes below zero" is not checked here but in the statement that takes a copy
 * ({@code BookOfferRepository.takeOne}): a check in Java would read the stock, and two purchases
 * could both read 1 before either writes (the lost update of the reservation lesson).
 */
@Entity
public class BookOffer {

    @Id
    private UUID id;

    // One offer per book.
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false, unique = true)
    private Book book;

    // Money in cents: a double cannot represent 0.10 exactly.
    @Column(nullable = false)
    private long priceCents;

    @Column(nullable = false)
    private int stock;

    protected BookOffer() {
    }

    public BookOffer(Book book, long priceCents, int stock) {
        if (priceCents <= 0) {
            throw new IllegalArgumentException("A book for sale has a positive price");
        }
        if (stock < 0) {
            throw new IllegalArgumentException("Stock cannot be negative");
        }
        this.id = UUID.randomUUID();
        this.book = Objects.requireNonNull(book);
        this.priceCents = priceCents;
        this.stock = stock;
    }

    public UUID getId() {
        return id;
    }

    public Book getBook() {
        return book;
    }

    public long getPriceCents() {
        return priceCents;
    }

    public int getStock() {
        return stock;
    }
}
