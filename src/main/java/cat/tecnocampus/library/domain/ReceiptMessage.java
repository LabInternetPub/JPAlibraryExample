package cat.tecnocampus.library.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The confirmation a member receives for a receipt, waiting to be sent (an outbox row).
 *
 * <p>It is written in the same transaction as the receipt: both commit or neither does. Sending
 * happens only after the commit, by reading these rows. So a message can never announce a receipt
 * that does not exist, and a message whose sending was interrupted is still here, pending, when the
 * application comes back.
 */
@Entity
public class ReceiptMessage {

    public enum Status { PENDING, SENT }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID purchaseId;

    @Column(nullable = false)
    private String receiptNumber;

    @Column(nullable = false)
    private String memberEmail;

    @Column(nullable = false, length = 2000)
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant sentAt;

    protected ReceiptMessage() {
    }

    private ReceiptMessage(Receipt receipt, String memberEmail, String text, Instant now) {
        this.id = UUID.randomUUID();
        this.purchaseId = receipt.getPurchase().getId();
        this.receiptNumber = receipt.getNumber();
        this.memberEmail = Objects.requireNonNull(memberEmail);
        this.text = text;
        this.status = Status.PENDING;
        this.createdAt = Objects.requireNonNull(now);
    }

    /** The confirmation of a receipt, to be sent after the commit. */
    public static ReceiptMessage confirming(Receipt receipt, Instant now) {
        Purchase purchase = receipt.getPurchase();
        return new ReceiptMessage(receipt, purchase.getMember().getEmail(), text(receipt, purchase), now);
    }

    /** The text of the confirmation. Also used by the naive version, which sends it at once. */
    public static String text(Receipt receipt, Purchase purchase) {
        String books = purchase.getLines().stream()
                .map(line -> line.getTitle() + " (" + euros(line.getPriceCents()) + ")")
                .collect(Collectors.joining(", "));
        return "Receipt " + receipt.getNumber() + ": " + books + ". Total " + euros(receipt.getTotalCents())
                + " " + receipt.getCurrency() + ", payment " + receipt.getProviderRef() + ".";
    }

    private static String euros(long cents) {
        return String.format(Locale.ROOT, "%d.%02d", cents / 100, cents % 100);
    }

    /** Sent: a later run must not send it again. */
    public void markSent(Instant now) {
        if (status == Status.SENT) {
            throw new IllegalStateException("Message " + id + " was already sent");
        }
        status = Status.SENT;
        sentAt = Objects.requireNonNull(now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getPurchaseId() {
        return purchaseId;
    }

    public String getReceiptNumber() {
        return receiptNumber;
    }

    public String getMemberEmail() {
        return memberEmail;
    }

    public String getText() {
        return text;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
