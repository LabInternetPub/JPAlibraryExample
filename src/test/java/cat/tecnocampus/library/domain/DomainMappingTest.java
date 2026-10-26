package cat.tecnocampus.library.domain;

import cat.tecnocampus.library.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnitUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/** The JPA mapping of the domain, against real PostgreSQL. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import(TestcontainersConfiguration.class)
class DomainMappingTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    @Autowired
    EntityManager entityManager;

    @Test
    void theWholeGraphIsStoredAndAssociationsAreLoadedLazily() {
        // Given
        Author author = new Author("Mercè Rodoreda", "Catalan");
        Subject novel = new Subject("Test subject");
        Book book = new Book("978-TEST-0001", "La plaça del Diamant", 1962, author);
        book.addSubject(novel);
        Branch branch = new Branch("Test branch", "Mataró");
        Copy copy = new Copy(branch, book, "N000001", 1, "1-A", Condition.GOOD);
        Member member = new Member("Test member", "test-member@example.org");
        Reservation reservation = new Reservation(member, List.of(copy.getId()), NOW);
        reservation.reserve(List.of(copy), NOW);
        List.of(author, novel, book, branch, copy, member, reservation).forEach(entityManager::persist);
        entityManager.flush();
        // Without clear(), find() would return the objects above from the first-level cache.
        entityManager.clear();

        // When
        Copy loaded = entityManager.find(Copy.class, copy.getId());

        // Then
        PersistenceUnitUtil util = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();
        assertThat(util.isLoaded(loaded, "book")).isFalse();
        assertThat(util.isLoaded(loaded, "branch")).isFalse();
        assertThat(loaded.getStatus()).isEqualTo(CopyStatus.RESERVED);
        assertThat(loaded.getReservationId()).isEqualTo(reservation.getId());

        Book loadedBook = loaded.getBook();
        assertThat(util.isLoaded(loadedBook, "author")).isFalse();
        assertThat(util.isLoaded(loadedBook, "subjects")).isFalse();
        assertThat(loadedBook.getSubjects()).extracting(Subject::getName).containsExactly("Test subject");

        Reservation loadedReservation = entityManager.find(Reservation.class, reservation.getId());
        assertThat(loadedReservation.getCopyIds()).containsExactly(copy.getId());
    }

    @Test
    void aBarcodeIsUniqueWithinABranch() {
        // Given
        Author author = new Author("Mercè Rodoreda", "Catalan");
        Book book = new Book("978-TEST-0001", "La plaça del Diamant", 1962, author);
        Branch branch = new Branch("Test branch", "Mataró");
        List.of(author, book, branch).forEach(entityManager::persist);
        entityManager.persist(new Copy(branch, book, "N000001", 1, "1-A", Condition.GOOD));

        // When / Then
        entityManager.persist(new Copy(branch, book, "N000001", 2, "2-B", Condition.NEW));
        assertThatThrownBy(entityManager::flush)
                .hasMessageContaining("uk_copy_branch_barcode");
    }
}
