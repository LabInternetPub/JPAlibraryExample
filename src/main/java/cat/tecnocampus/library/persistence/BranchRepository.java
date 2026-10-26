package cat.tecnocampus.library.persistence;

import cat.tecnocampus.library.domain.Branch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BranchRepository extends JpaRepository<Branch, UUID> {

    Optional<Branch> findByName(String name);
}
