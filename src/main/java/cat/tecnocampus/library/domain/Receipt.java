package cat.tecnocampus.library.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * The buyer's proof of a paid purchase. The library's version of Affora's ticket.
 *
 * <p>The books, prices and total are those frozen in the purchase, which never changes once paid;
 * the receipt adds its number, the provider's reference and the moment it was issued.
 */
@Entity
public class Receipt {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String number;

    // unique: a purchase can never have two receipts, whatever the code above does.
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false, unique = true)
    private Purchase purchase;

    @Column(nullable = false)
    private long totalCents;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String providerRef;

    @Column(nullable = false)
    private Instant issuedAt;

    protected Receipt() {
    }

    private Receipt(Purchase purchase, Instant now) {
        this.id = UUID.randomUUID();
        this.purchase = purchase;
        this.number = "R-" + purchase.getId().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        this.totalCents = purchase.getTotalCents();
        this.currency = purchase.getCurrency();
        this.providerRef = purchase.getProviderRef();
        this.issuedAt = Objects.requireNonNull(now);
    }

    /** A receipt exists only for a paid purchase: never for one that is pending, unknown or refused. */
    public static Receipt issueFor(Purchase purchase, Instant now) {
        if (purchase.getStatus() != PurchaseStatus.PAID) {
            throw new IllegalStateException("No receipt for a purchase that is " + purchase.getStatus());
        }
        return new Receipt(purchase, now);
    }

    public UUID getId() {
        return id;
    }

    public String getNumber() {
        return number;
    }

    public Purchase getPurchase() {
        return purchase;
    }

    public long getTotalCents() {
        return totalCents;
    }

    public String getCurrency() {
        return currency;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }
}
