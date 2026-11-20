package cat.tecnocampus.library.persistence;

import cat.tecnocampus.library.domain.Receipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID> {

    Optional<Receipt> findByPurchaseId(UUID purchaseId);

    @Query("select count(r) from Receipt r where r.purchase.member.id = :memberId")
    long countByMemberId(@Param("memberId") UUID memberId);
}
