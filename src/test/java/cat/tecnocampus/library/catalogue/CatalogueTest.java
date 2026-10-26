package cat.tecnocampus.library.catalogue;

import cat.tecnocampus.library.TestcontainersConfiguration;
import cat.tecnocampus.library.domain.Book;
import cat.tecnocampus.library.persistence.BookRepository;
import cat.tecnocampus.library.scenario.Measured;
import cat.tecnocampus.library.scenario.Measurement;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CatalogueTest {

    @Autowired NaiveCatalogueService naive;
    @Autowired JoinFetchCatalogueService joinFetch;
    @Autowired ProjectionCatalogueService projection;
    @Autowired Measurement measurement;
    @Autowired BookRepository books;
    @Autowired TransactionTemplate transactions;
    @Autowired MockMvcTester mvc;

    @Test
    void theThreeVersionsReturnTheSameCatalogue() {
        // When
        List<BookSummary> expected = naive.list();

        // Then
        assertThat(expected).hasSize(321);
        assertThat(joinFetch.list()).isEqualTo(expected);
        assertThat(projection.list()).isEqualTo(expected);
        assertThat(expected).filteredOn(book -> book.title().equals("The Last Copy")).singleElement()
                .satisfies(book -> {
                    assertThat(book.authorName()).isEqualTo("Bestselling Author");
                    assertThat(book.subjects()).isNotEmpty().isSorted();
                });
    }

    @Test
    void theNaiveVersionSendsOneStatementForTheBooksPlusOnePerAuthorAndOnePerBook() {
        // When
        Measured<List<BookSummary>> measured = measurement.sql(naive::list);

        // Then: 1 + 302 distinct authors + 321 subject lists
        assertThat(measured.sql().jdbcStatements()).isEqualTo(1 + 302 + 321);
        assertThat(measured.sql().collectionFetches()).isEqualTo(321);
    }

    @Test
    void theFirstLevelCacheAnswersRepeatedAuthorsOfTheNaiveVersion() {
        // When
        Measured<List<BookSummary>> measured = measurement.sql(() -> naive.listByAuthor("Prolific Author"));

        // Then: 1 + 1 author (not 20) + 20 subject lists
        assertThat(measured.value()).hasSize(20);
        assertThat(measured.sql().jdbcStatements()).isEqualTo(22);
    }

    @Test
    void joinFetchSendsOneStatementButStillLoadsEntities() {
        // When
        Measured<List<BookSummary>> measured = measurement.sql(joinFetch::list);

        // Then: 321 books + 302 authors + 20 subjects
        assertThat(measured.sql().jdbcStatements()).isEqualTo(1);
        assertThat(measured.sql().entityLoads()).isEqualTo(643);
    }

    @Test
    void theProjectionSendsOneStatementAndLoadsNoEntity() {
        // When
        Measured<List<BookSummary>> measured = measurement.sql(projection::list);

        // Then
        assertThat(measured.sql().jdbcStatements()).isEqualTo(1);
        assertThat(measured.sql().entityLoads()).isZero();
    }

    @Test
    void aLazyAssociationCannotBeLoadedAfterTheTransactionHasEnded() {
        // Given: the books leave the transaction with their authors still unloaded
        List<Book> detached = transactions.execute(status -> books.findAllOrderedByTitle());

        // When / Then: with open-in-view=false, nothing reopens a session to load them
        assertThatThrownBy(() -> detached.getFirst().getAuthor().getName())
                .isInstanceOf(LazyInitializationException.class);
    }

    @Test
    void theEndpointsReturnTheCatalogue() {
        assertThat(mvc.get().uri("/naive/books")).hasStatusOk()
                .bodyJson().extractingPath("$.length()").isEqualTo(321);
        assertThat(mvc.get().uri("/naive/books").param("author", "Prolific Author")).hasStatusOk()
                .bodyJson().extractingPath("$.length()").isEqualTo(20);
        assertThat(mvc.get().uri("/better/books/join-fetch")).hasStatusOk()
                .bodyJson().extractingPath("$[0].authorName").isNotNull();
        assertThat(mvc.get().uri("/better/books/projection")).hasStatusOk()
                .bodyJson().extractingPath("$.length()").isEqualTo(321);
    }
}
