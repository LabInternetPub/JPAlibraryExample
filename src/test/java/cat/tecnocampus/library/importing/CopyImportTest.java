package cat.tecnocampus.library.importing;

import cat.tecnocampus.library.TestcontainersConfiguration;
import cat.tecnocampus.library.domain.Condition;
import cat.tecnocampus.library.domain.Copy;
import cat.tecnocampus.library.persistence.BarcodeRef;
import cat.tecnocampus.library.persistence.BranchRepository;
import cat.tecnocampus.library.persistence.CopyRepository;
import cat.tecnocampus.library.scenario.DemoReset;
import cat.tecnocampus.library.scenario.Measured;
import cat.tecnocampus.library.scenario.Measurement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Both imports follow the same rules; only their cost differs. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CopyImportTest {

    private static final String SMALL = """
            isbn,barcode,floor,shelf,condition
            978-84-000001,T1,1,1-01,GOOD
            978-84-000002,T2,1,1-01,NEW
            978-84-000003,T3,2,2-01,WORN
            """;

    @Autowired NaiveCopyImportService naive;
    @Autowired BatchedCopyImportService batched;
    @Autowired DemoReset reset;
    @Autowired BranchRepository branches;
    @Autowired CopyRepository copies;
    @Autowired Measurement measurement;
    @Autowired MockMvcTester mvc;

    @BeforeEach
    void emptyAnnex() {
        reset.emptyBranch("Annex");
    }

    @Test
    void aSmallFileCreatesItsCopiesWithBothVersions() {
        for (BiFunction<String, InputStream, ImportResult> importer : importers()) {
            // Given
            reset.emptyBranch("Annex");

            // When
            ImportResult result = importer.apply("Annex", csv(SMALL));

            // Then
            assertThat(result).isEqualTo(new ImportResult("Annex", 3, 3, 0, 0));
            assertThat(annex()).containsOnlyKeys("T1", "T2", "T3");
        }
    }

    @Test
    void importingTheSameFileAgainKeepsTheCopiesAndTheirIdsAndUpdatesOnlyWhatChanged() {
        for (BiFunction<String, InputStream, ImportResult> importer : importers()) {
            // Given
            reset.emptyBranch("Annex");
            importer.apply("Annex", csv(SMALL));
            Map<String, UUID> before = annex();

            // When: the same file, then the same file with T2 moved to another shelf
            ImportResult same = importer.apply("Annex", csv(SMALL));
            ImportResult changed = importer.apply("Annex", csv(SMALL.replace("T2,1,1-01,NEW", "T2,3,3-05,NEW")));

            // Then
            assertThat(same).isEqualTo(new ImportResult("Annex", 3, 0, 0, 3));
            assertThat(changed).isEqualTo(new ImportResult("Annex", 3, 0, 1, 2));
            assertThat(annex()).isEqualTo(before);
            Copy moved = copies.findById(before.get("T2")).orElseThrow();
            assertThat(moved.getShelf()).isEqualTo("3-05");
            assertThat(moved.getCondition()).isEqualTo(Condition.NEW);
        }
    }

    @Test
    void anUnknownIsbnOnTheLastRowImportsNothing() {
        // Given
        String csv = SMALL + "978-84-777777,T4,2,2-01,GOOD\n";

        for (BiFunction<String, InputStream, ImportResult> importer : importers()) {
            // When / Then: the naive version wrote three copies before failing; the rollback undoes them
            assertThatThrownBy(() -> importer.apply("Annex", csv(csv)))
                    .isInstanceOfSatisfying(InvalidImportFileException.class, e -> assertThat(e.getLine()).isEqualTo(5));
            assertThat(annex()).isEmpty();
        }
    }

    @Test
    void theNaiveImportSendsThreeStatementsAndABatchOfOneInsertPerNewRow() {
        // Given: 1,000 new copies
        String csv = rows(1_000);

        // When
        Measured<ImportResult> measured = measurement.withoutSqlLog(() -> measurement.sql(() -> naive.importCopies("Annex", csv(csv))));

        // Then: branch + per row 3 SELECTs (book, copy, merge); auto-flush sends each INSERT as a batch of one
        assertThat(measured.sql().jdbcStatements()).isEqualTo(1 + 3 * 1_000);
        assertThat(measured.sql().jdbcBatches()).isEqualTo(1_000);
    }

    @Test
    void theBatchedImportSendsOneBatchPerChunk() {
        // Given: 1,000 new copies, then the same again
        String csv = rows(1_000);

        // When
        Measured<ImportResult> first = measurement.withoutSqlLog(() -> measurement.sql(() -> batched.importCopies("Annex", csv(csv))));
        Measured<ImportResult> again = measurement.withoutSqlLog(() -> measurement.sql(() -> batched.importCopies("Annex", csv(csv))));

        // Then: branch, ISBNs, barcodes, then one batch per 500 rows; again: one SELECT per chunk, no UPDATE
        assertThat(first.sql().jdbcStatements()).isEqualTo(3);
        assertThat(first.sql().jdbcBatches()).isEqualTo(2);
        assertThat(again.sql().jdbcStatements()).isEqualTo(3 + 2);
        assertThat(again.sql().jdbcBatches()).isZero();
        assertThat(again.sql().entityUpdates()).isZero();
    }

    @Test
    void theEndpointsImportAnUploadedFileAndRejectABadOneWith400() {
        MockMultipartFile good = new MockMultipartFile("file", "copies.csv", "text/csv", SMALL.getBytes(StandardCharsets.UTF_8));
        MockMultipartFile bad = new MockMultipartFile("file", "copies.csv", "text/csv",
                (SMALL + "978-84-777777,T4,2,2-01,GOOD\n").getBytes(StandardCharsets.UTF_8));

        assertThat(mvc.post().uri("/better/branches/Annex/copies/import").multipart().file(good)).hasStatusOk()
                .bodyJson().extractingPath("$.created").isEqualTo(3);
        assertThat(mvc.post().uri("/naive/branches/Annex/copies/import").multipart().file(good)).hasStatusOk()
                .bodyJson().extractingPath("$.unchanged").isEqualTo(3);
        assertThat(mvc.post().uri("/naive/branches/Annex/copies/import").multipart().file(bad))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.line").isEqualTo(5);
    }

    private BiFunction<String, InputStream, ImportResult>[] importers() {
        @SuppressWarnings("unchecked")
        BiFunction<String, InputStream, ImportResult>[] both = new BiFunction[] {
                (BiFunction<String, InputStream, ImportResult>) naive::importCopies,
                (BiFunction<String, InputStream, ImportResult>) batched::importCopies};
        return both;
    }

    private Map<String, UUID> annex() {
        UUID annex = branches.findByName("Annex").orElseThrow().getId();
        return copies.findBarcodeIndex(annex).stream().collect(Collectors.toMap(BarcodeRef::barcode, BarcodeRef::id));
    }

    private static String rows(int count) {
        return "isbn,barcode,floor,shelf,condition\n" + IntStream.rangeClosed(1, count)
                .mapToObj(i -> "978-84-%06d,V%05d,1,1-01,GOOD".formatted(i % 300 + 1, i))
                .collect(Collectors.joining("\n", "", "\n"));
    }

    private static InputStream csv(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
