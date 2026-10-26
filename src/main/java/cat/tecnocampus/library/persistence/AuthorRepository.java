package cat.tecnocampus.library.persistence;

import cat.tecnocampus.library.domain.Author;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuthorRepository extends JpaRepository<Author, UUID> {
}
