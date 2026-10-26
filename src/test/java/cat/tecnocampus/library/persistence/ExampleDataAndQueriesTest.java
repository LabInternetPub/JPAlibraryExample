package cat.tecnocampus.library.persistence;

import cat.tecnocampus.library.TestcontainersConfiguration;
import cat.tecnocampus.library.domain.Book;
import cat.tecnocampus.library.domain.Copy;
import cat.tecnocampus.library.domain.CopyStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnitUtil;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/** The data loaded by data.sql, read through the repository queries the lessons use. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import(TestcontainersConfiguration.class)
class ExampleDataAndQueriesTest {

    @Autowired EntityManager entityManager;
    @Autowired BookRepository books;
    @Autowired CopyRepository copies;
    @Autowired BranchRepository branches;
    @Autowired MemberRepository members;
    @Autowired ReservationRepository reservations;

    private Statistics statistics;
    private UUID central;

    @BeforeEach
    void setUp() {
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        central = branches.findByName("Central").orElseThrow().getId();
        entityManager.clear();
        statistics.clear();
    }

    @Test
    void theCatalogueHas321BooksAndTheirIsbnsLoadInOneQuery() {
        // When
        List<IsbnRef> index = books.findIsbnIndex();

        // Then
        assertThat(index).hasSize(321);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    void joinFetchLoadsBooksAuthorsAndSubjectsInOneStatement() {
        // When
        List<Book> all = books.findAllWithAuthorAndSubjects();
        all.forEach(book -> book.getAuthor().getName());
        all.forEach(book -> book.getSubjects().size());

        // Then
        assertThat(all).hasSize(321);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void walkingTheLazyAssociationsIsAnNPlusOne() {
        // When
        List<Book> all = books.findAllOrderedByTitle();
        all.forEach(book -> book.getAuthor().getName());
        all.forEach(book -> book.getSubjects().size());

        // Then: 1 for the books + 302 distinct authors + 321 subject collections
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1 + 302 + 321);
    }

    @Test
    void theProlificAuthorIsLoadedOnceForItsTwentyBooks() {
        // When
        List<Book> prolific = books.findByAuthorNameOrderedByTitle("Prolific Author");
        prolific.forEach(book -> book.getAuthor().getName());

        // Then: the first-level cache answers the other 19 lookups
        assertThat(prolific).hasSize(20);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void theCatalogueProjectionReturnsScalarsOnly() {
        // When
        List<BookRow> rows = books.findCatalogueRows();

        // Then: one row per book and subject; books have 2 to 4 subjects
        assertThat(rows.stream().map(BookRow::bookId).distinct()).hasSize(321);
        assertThat(rows).hasSizeBetween(321 * 2, 321 * 4);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    void centralHas20000CopiesWithTheExpectedStoredStatuses() {
        // When
        Map<CopyStatus, Long> byStatus = copies.findByBranchOrdered(central).stream()
                .collect(Collectors.groupingBy(Copy::getStatus, Collectors.counting()));

        // Then
        assertThat(byStatus).containsOnly(
                Map.entry(CopyStatus.AVAILABLE, 18_450L),
                Map.entry(CopyStatus.ON_LOAN, 1_000L),
                Map.entry(CopyStatus.RESERVED, 500L),
                Map.entry(CopyStatus.WITHDRAWN, 50L));
        assertThat(copies.findBarcodeIndex(central)).hasSize(20_000);
    }

    @Test
    void theShelfMapProjectionShowsExpiredReservationsAsAvailable() {
        // When
        List<ShelfRow> rows = copies.projectShelfMap(central, Instant.now());

        // Then: 100 of the 500 reservations have expired
        Map<CopyStatus, Long> byStatus = rows.stream()
                .collect(Collectors.groupingBy(ShelfRow::status, Collectors.counting()));
        assertThat(rows).hasSize(20_000);
        assertThat(byStatus).containsEntry(CopyStatus.RESERVED, 400L).containsEntry(CopyStatus.AVAILABLE, 18_550L);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    void theNaiveShelfMapLoadsCopiesWhoseBooksAreLazyProxies() {
        // When
        List<Copy> all = copies.findByBranchOrdered(central);

        // Then
        PersistenceUnitUtil util = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();
        assertThat(all).hasSize(20_000);
        assertThat(util.isLoaded(all.getFirst(), "book")).isFalse();
        assertThat(statistics.getEntityLoadCount()).isEqualTo(20_000);
    }

    @Test
    void readersHaveThreeActiveItemsAndScenarioMembersNone() {
        // Given
        // reader002, not reader001: reader001's reservation (copy C000001) is one of the expired ones
        UUID reader = members.findByEmail("reader002@library.test").orElseThrow().getId();
        UUID member = members.findByEmail("member01@library.test").orElseThrow().getId();

        // When / Then: two open loans and one live reservation
        assertThat(reservations.countActiveItems(reader, Instant.now())).isEqualTo(3);
        assertThat(reservations.countActiveItems(member, Instant.now())).isZero();
    }

    @Test
    void anExpiredReservationNoLongerCountsAsAnActiveItem() {
        // Given: copy C000001 has a reservation that expired a day ago
        Copy expired = copies.findByBranchIdAndBarcode(central, "C000001").orElseThrow();
        UUID owner = reservations.findById(expired.getReservationId()).orElseThrow().getMember().getId();

        // When / Then: that reader keeps only the two loans
        assertThat(reservations.countActiveItems(owner, Instant.now())).isEqualTo(2);
    }

    @Test
    void northHasTheBestsellerAndThirtyOrdinaryCopiesAndAnnexIsEmpty() {
        // Given
        UUID north = branches.findByName("North").orElseThrow().getId();
        UUID annex = branches.findByName("Annex").orElseThrow().getId();

        // When
        Map<String, Copy> northCopies = copies.findByBranchOrdered(north).stream()
                .collect(Collectors.toMap(Copy::getBarcode, Function.identity()));

        // Then
        assertThat(northCopies).hasSize(31).containsKey("N-BEST");
        assertThat(northCopies.get("N-BEST").getBook().getTitle()).isEqualTo("The Last Copy");
        assertThat(copies.findBarcodeIndex(annex)).isEmpty();
    }
}
