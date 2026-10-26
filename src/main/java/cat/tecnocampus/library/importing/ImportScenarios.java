package cat.tecnocampus.library.importing;

import cat.tecnocampus.library.persistence.BarcodeRef;
import cat.tecnocampus.library.persistence.BranchRepository;
import cat.tecnocampus.library.persistence.CopyRepository;
import cat.tecnocampus.library.scenario.DemoReset;
import cat.tecnocampus.library.scenario.Measurement;
import cat.tecnocampus.library.scenario.Scenario;
import cat.tecnocampus.library.scenario.ScenarioReport;
import cat.tecnocampus.library.scenario.SimpleScenario;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * The import scenarios. For each file: empty Annex, import it, then import the same file again.
 * Each import is measured once (SQL, time, peak heap) with the SQL log off.
 *
 * <p>The naive scenario imports 5,000 rows: its cost grows with the square of the rows (each
 * auto-flush dirty-checks every copy managed so far), and 20,000 rows take several minutes,
 * too long for a class. The batched scenario imports the same 5,000 rows, for a direct
 * comparison, and then the full 20,000.
 */
@Configuration
class ImportScenarios {

    static final String BRANCH = "Annex";
    static final String ROWS_5000 = "import/annex-5000.csv";
    static final String ROWS_20000 = "import/annex-20000.csv";
    private static final String LESSON = "import";

    private final Measurement measurement;
    private final DemoReset reset;
    private final BranchRepository branches;
    private final CopyRepository copies;

    ImportScenarios(Measurement measurement, DemoReset reset, BranchRepository branches, CopyRepository copies) {
        this.measurement = measurement;
        this.reset = reset;
        this.branches = branches;
        this.copies = copies;
    }

    @Bean
    Scenario importNaive(NaiveCopyImportService naive) {
        return new SimpleScenario("import-naive", LESSON,
                "Look up, create or update and save() each row: 5,000 rows, imported twice",
                scenario -> {
                    ScenarioReport.Builder report = ScenarioReport.of(scenario);
                    importTwice(report, naive::importCopies, ROWS_5000);
                    return report
                            .note("First import: per row, 1 SELECT of the book, 1 of the copy, 1 by save() -> merge(), "
                                    + "and the INSERT.")
                            .note("Batching is configured (500), yet each batch carries one INSERT: the next row's copy "
                                    + "lookup auto-flushes the pending insert. 5,000 rows, 5,000 batches.")
                            .note("Re-import: half the statements of the first import and still slow: every auto-flush "
                                    + "dirty-checks every copy managed so far, so the cost grows with the square of the rows.")
                            .note("Not run at 20,000 rows: four times the rows is about sixteen times the time.")
                            .build();
                });
    }

    @Bean
    Scenario importBatched(BatchedCopyImportService batched) {
        return new SimpleScenario("import-batched", LESSON,
                "One lookup for all ISBNs and barcodes, persist(), flush + clear every 500 rows: 5,000 and 20,000 rows",
                scenario -> {
                    ScenarioReport.Builder report = ScenarioReport.of(scenario);
                    importTwice(report, batched::importCopies, ROWS_5000);
                    importTwice(report, batched::importCopies, ROWS_20000);
                    return report
                            .note("First import: 3 lookups, then one JDBC batch of 500 inserts per chunk.")
                            .note("Re-import: one SELECT of the existing copies per chunk, no UPDATE (nothing changed).")
                            .note("Four times the rows is about four times the work: the cost grows linearly.")
                            .note("Still one transaction: a bad row anywhere imports nothing.")
                            .build();
                });
    }

    private void importTwice(ScenarioReport.Builder report, BiFunction<String, InputStream, ImportResult> importer,
                             String file) {
        byte[] csv = read(file);
        reset.emptyBranch(BRANCH);
        Measurement.SingleRun<ImportResult> first = importOnce(importer, csv);
        Map<String, UUID> idsAfterFirst = annex();
        Measurement.SingleRun<ImportResult> second = importOnce(importer, csv);

        String rows = String.format(Locale.ROOT, "%,d rows", first.value().rows());
        add(report, rows + ", first import", first);
        add(report, rows + ", re-import", second);
        report.result(rows + ", same ids after the re-import", idsAfterFirst.equals(annex()));
    }

    private Measurement.SingleRun<ImportResult> importOnce(BiFunction<String, InputStream, ImportResult> importer,
                                                           byte[] csv) {
        return measurement.withoutSqlLog(
                () -> measurement.once(() -> importer.apply(BRANCH, new ByteArrayInputStream(csv))));
    }

    private static void add(ScenarioReport.Builder report, String prefix, Measurement.SingleRun<ImportResult> run) {
        report.result(prefix, run.value())
                .result(prefix + ": time", run.time())
                .result(prefix + ": peak heap growth", run.heap().peakIncrease())
                .result(prefix + ": JDBC statements", run.sql().jdbcStatements())
                .result(prefix + ": JDBC batches", run.sql().jdbcBatches())
                .result(prefix + ": entities loaded", run.sql().entityLoads());
    }

    private Map<String, UUID> annex() {
        UUID annex = branches.findByName(BRANCH).orElseThrow().getId();
        return copies.findBarcodeIndex(annex).stream().collect(Collectors.toMap(BarcodeRef::barcode, BarcodeRef::id));
    }

    private static byte[] read(String file) {
        try (InputStream in = new ClassPathResource(file).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
