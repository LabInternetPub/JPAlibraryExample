package cat.tecnocampus.library.persistence;

import cat.tecnocampus.library.domain.ReceiptMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReceiptMessageRepository extends JpaRepository<ReceiptMessage, UUID> {

    /** The oldest pending messages: one bounded page per run of the dispatcher. */
    @Query("""
            select m.id from ReceiptMessage m
            where m.status = cat.tecnocampus.library.domain.ReceiptMessage.Status.PENDING
            order by m.createdAt
            """)
    List<UUID> findPendingIds(Pageable page);

    /**
     * The message, locked, if it is still pending and nobody else is sending it right now.
     * {@code SKIP LOCKED}: the after-commit delivery and the scheduled job may look at the same
     * message at the same instant; the second one finds no row and moves on instead of sending it
     * a second time. Native SQL: JPQL has no "skip locked".
     */
    @Query(value = """
            select * from receipt_message
            where id = :id and status = 'PENDING'
            for update skip locked
            """, nativeQuery = true)
    Optional<ReceiptMessage> lockIfPending(@Param("id") UUID id);

    long countByStatus(ReceiptMessage.Status status);
}
