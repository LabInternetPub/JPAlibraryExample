package cat.tecnocampus.library.persistence;

import cat.tecnocampus.library.domain.Loan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LoanRepository extends JpaRepository<Loan, UUID> {

    /** Open loans of one copy. More than one is a copy lent twice; the expiry scenarios check it. */
    @Query("select count(l) from Loan l where l.copy.id = :copy and l.returned = false")
    long countOpenByCopy(@Param("copy") UUID copyId);

    /** The copy of a loan, as a scalar. */
    @Query("select l.copy.id from Loan l where l.id = :id")
    Optional<UUID> findCopyId(@Param("id") UUID loanId);

    /**
     * Return, better: closes the loan only if it is still open. 1: this call returned it; 0: it
     * was already returned (a retry, or a second scan at the same time), so nothing else to do.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Loan l set l.returned = true, l.returnedAt = :now where l.id = :id and l.returned = false")
    int closeIfOpen(@Param("id") UUID loanId, @Param("now") Instant now);
}
