package cat.tecnocampus.library.persistence;

import cat.tecnocampus.library.domain.Loan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface LoanRepository extends JpaRepository<Loan, UUID> {

    /** Open loans of one copy. More than one is a copy lent twice; the expiry scenarios check it. */
    @Query("select count(l) from Loan l where l.copy.id = :copy and l.returned = false")
    long countOpenByCopy(@Param("copy") UUID copyId);
}
