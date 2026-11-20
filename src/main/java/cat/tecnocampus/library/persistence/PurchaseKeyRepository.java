package cat.tecnocampus.library.persistence;

import cat.tecnocampus.library.domain.PurchaseKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PurchaseKeyRepository extends JpaRepository<PurchaseKey, UUID> {

    Optional<PurchaseKey> findByMemberIdAndIdempotencyKey(UUID memberId, String idempotencyKey);
}
