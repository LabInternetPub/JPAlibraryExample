package cat.tecnocampus.library.shop;

import cat.tecnocampus.library.domain.Member;
import cat.tecnocampus.library.domain.Purchase;
import cat.tecnocampus.library.domain.PurchaseKey;
import cat.tecnocampus.library.domain.ShopExceptions.IdempotencyKeyReusedException;
import cat.tecnocampus.library.persistence.PurchaseKeyRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Step 1 of the better purchase, in one short transaction: the key, the stock, the purchase.
 * No network call here.
 */
@Service
public class PurchasePreparation {

    private final PurchaseReads reads;
    private final PurchaseKeyRepository keys;
    private final ShopStock stock;
    private final EntityManager entityManager;
    private final Clock clock;

    public PurchasePreparation(PurchaseReads reads, PurchaseKeyRepository keys, ShopStock stock,
                               EntityManager entityManager, Clock clock) {
        this.reads = reads;
        this.keys = keys;
        this.stock = stock;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * Returns the id of the purchase this key stands for, creating it if the key is new.
     *
     * <p>The key row is inserted <em>first</em> and flushed at once. If another transaction has
     * inserted the same (member, key) and not committed yet, PostgreSQL makes this insert wait on
     * the unique index; when the other one commits, this one fails ({@link KeyTakenException}) and
     * rolls back, before having taken any stock.
     * The caller then reads the winner's purchase in a new transaction ({@link #afterCollision}).
     */
    @Transactional(timeout = 10)
    public UUID prepare(PurchaseCommand command) {
        Member member = reads.member(command.memberEmail());
        Optional<UUID> known = knownPurchase(member.getId(), command);
        if (known.isPresent()) {
            return known.get();       // a retry that arrives after the first request committed
        }

        UUID purchaseId = UUID.randomUUID();
        entityManager.persist(new PurchaseKey(member.getId(), command.idempotencyKey(), command.fingerprint(), purchaseId));
        flushKey();                   // the unique index decides now, not at commit

        ShopStock.Taken taken = stock.take(command.isbns());
        entityManager.persist(Purchase.awaitingPayment(purchaseId, member, command.idempotencyKey(),
                command.fingerprint(), taken.lines(), taken.skippedIsbns(), clock.instant()));
        return purchaseId;
    }

    /** The purchase of the request that won the insert of the key. A fresh transaction: the failed one is gone. */
    @Transactional(readOnly = true)
    public UUID afterCollision(PurchaseCommand command) {
        Member member = reads.member(command.memberEmail());
        return knownPurchase(member.getId(), command)
                .orElseThrow(() -> new IllegalStateException("Key collision, but no key row for " + command.idempotencyKey()));
    }

    /**
     * Thrown when the key's insert lost against another request with the same key. The transaction
     * rolls back; the coordinator then calls {@link #afterCollision}.
     */
    public static class KeyTakenException extends RuntimeException {

        KeyTakenException(Throwable cause) {
            super("The idempotency key was inserted by another request", cause);
        }
    }

    /**
     * {@code EntityManager.flush} throws Hibernate's exception, not Spring's
     * {@code DataIntegrityViolationException} (only repositories translate exceptions). Only the
     * key's own constraint means "another request won"; any other failure stays what it is.
     */
    private void flushKey() {
        try {
            entityManager.flush();
        } catch (ConstraintViolationException e) {
            if (PurchaseKey.UNIQUE_MEMBER_KEY.equalsIgnoreCase(e.getConstraintName())) {
                throw new KeyTakenException(e);
            }
            throw e;
        }
    }

    private Optional<UUID> knownPurchase(UUID memberId, PurchaseCommand command) {
        Optional<PurchaseKey> key = keys.findByMemberIdAndIdempotencyKey(memberId, command.idempotencyKey());
        if (key.isPresent() && !key.get().isSameRequest(command.fingerprint())) {
            throw new IdempotencyKeyReusedException(command.idempotencyKey());
        }
        return key.map(PurchaseKey::getPurchaseId);
    }
}
