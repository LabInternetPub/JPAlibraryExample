package cat.tecnocampus.library.persistence;

import cat.tecnocampus.library.domain.Purchase;
import cat.tecnocampus.library.domain.PurchaseStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PurchaseRepository extends JpaRepository<Purchase, UUID> {

    /**
     * Naive idempotency: "is there already a purchase with this key?". First, not single: once the
     * naive version has created duplicates, a single-result query would throw.
     */
    Optional<Purchase> findFirstByMemberIdAndIdempotencyKeyOrderByCreatedAt(UUID memberId, String idempotencyKey);

    /**
     * The purchase, locked: claiming the payment and recording its result are decided one request
     * at a time. Short transactions only: nobody holds this lock during the call to the provider.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select p from Purchase p where p.id = :id")
    Optional<Purchase> lockById(@Param("id") UUID id);

    long countByMemberId(UUID memberId);

    long countByMemberIdAndIdempotencyKey(UUID memberId, String idempotencyKey);

    long countByStatus(PurchaseStatus status);
}
