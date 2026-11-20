package cat.tecnocampus.library.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Objects;
import java.util.UUID;

/**
 * "This member has used this idempotency key, for this purchase." Used by the better version only.
 *
 * <p>The row exists for its unique constraint: of ten simultaneous inserts of the same
 * (member, key), PostgreSQL lets exactly one commit; the others wait on the index and then fail.
 * A "look for it first, insert if missing" in Java cannot give that guarantee: all ten look before
 * any of them inserts. The naive version stores the key on the purchase only, with no constraint,
 * so the duplicates it creates can be counted.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = PurchaseKey.UNIQUE_MEMBER_KEY,
        columnNames = {"member_id", "idempotency_key"}))
public class PurchaseKey {

    public static final String UNIQUE_MEMBER_KEY = "uk_purchase_key_member_key";

    @Id
    private UUID id;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(nullable = false)
    private UUID purchaseId;

    protected PurchaseKey() {
    }

    public PurchaseKey(UUID memberId, String idempotencyKey, String fingerprint, UUID purchaseId) {
        this.id = UUID.randomUUID();
        this.memberId = Objects.requireNonNull(memberId);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.fingerprint = Objects.requireNonNull(fingerprint);
        this.purchaseId = Objects.requireNonNull(purchaseId);
    }

    public boolean isSameRequest(String otherFingerprint) {
        return fingerprint.equals(otherFingerprint);
    }

    public UUID getPurchaseId() {
        return purchaseId;
    }
}
