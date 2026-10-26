package cat.tecnocampus.library.persistence;

import cat.tecnocampus.library.domain.Member;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface MemberRepository extends JpaRepository<Member, UUID> {

    Optional<Member> findByEmail(String email);

    /**
     * Member limit, better: every request of the same member locks this one row first, so two
     * simultaneous requests of the same member run one after the other. Requests of different
     * members do not wait for each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select m from Member m where m.id = :id")
    Optional<Member> lockById(@Param("id") UUID id);
}
