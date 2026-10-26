package cat.tecnocampus.library.scenario;

import cat.tecnocampus.library.TestcontainersConfiguration;
import cat.tecnocampus.library.domain.Subject;
import cat.tecnocampus.library.persistence.BookRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ScenarioInfrastructureTest {

    @Autowired Measurement measurement;
    @Autowired BookRepository books;
    @Autowired TransactionTemplate transactions;
    @Autowired EntityManager entityManager;
    @Autowired ScenarioRegistry registry;
    @Autowired MockMvcTester mvc;

    @Test
    void aQueryCountsAsOneJdbcStatement() {
        // When
        Measured<Integer> measured = measurement.sql(() -> books.findIsbnIndex().size());

        // Then
        assertThat(measured.value()).isEqualTo(321);
        assertThat(measured.sql().jdbcStatements()).isEqualTo(1);
        assertThat(measured.sql().jdbcBatches()).isZero();
        assertThat(measured.sql().entityLoads()).isZero();
    }

    @Test
    void batchedInsertsCountAsBatchesNotAsStatements() {
        // When: 25 inserts with a batch size of 10, rolled back afterwards
        Measured<Void> measured = measurement.sql(() -> transactions.execute(status -> {
            entityManager.unwrap(Session.class).setJdbcBatchSize(10);
            for (int i = 0; i < 25; i++) {
                entityManager.persist(new Subject("Batch test " + i));
            }
            entityManager.flush();
            status.setRollbackOnly();
            return null;
        }));

        // Then: 10 + 10 + 5
        assertThat(measured.sql().jdbcBatches()).isEqualTo(3);
        assertThat(measured.sql().jdbcStatements()).isZero();
        assertThat(measured.sql().entityInserts()).isEqualTo(25);
    }

    @Test
    void theFirstLevelCacheScenarioShowsOneStatementInOneTransactionAndTwoInTwo() {
        // When
        ScenarioReport report = registry.find("first-level-cache").orElseThrow().run();

        // Then
        assertThat(report.results())
                .containsEntry("one transaction: JDBC statements", 1L)
                .containsEntry("one transaction: same Java object", true)
                .containsEntry("two transactions: JDBC statements", 2L)
                .containsEntry("two transactions: same Java object", false);
    }

    @Test
    void scenariosAreListedAndRunOverHttp() {
        assertThat(mvc.get().uri("/scenarios")).hasStatusOk()
                .bodyJson().extractingPath("$[*].name").asArray().contains("first-level-cache");
        assertThat(mvc.post().uri("/scenarios/first-level-cache")).hasStatusOk()
                .bodyJson().extractingPath("$.results['two transactions: JDBC statements']").isEqualTo(2);
        assertThat(mvc.post().uri("/scenarios/does-not-exist")).hasStatus(HttpStatus.NOT_FOUND);
    }
}
