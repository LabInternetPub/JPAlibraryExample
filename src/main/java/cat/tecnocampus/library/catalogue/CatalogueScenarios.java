package cat.tecnocampus.library.catalogue;

import cat.tecnocampus.library.scenario.Measured;
import cat.tecnocampus.library.scenario.Measurement;
import cat.tecnocampus.library.scenario.Scenario;
import cat.tecnocampus.library.scenario.ScenarioReport;
import cat.tecnocampus.library.scenario.Timing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.function.Supplier;

/**
 * The three catalogue scenarios. They measure the same things in the same way: one call with
 * the SQL counted (and logged), then 3 warm-ups and 10 timed calls with the SQL log silenced.
 * Every call goes through a service, so every call is its own transaction.
 */
@Configuration
class CatalogueScenarios {

    private static final int WARMUPS = 3;
    private static final int SAMPLES = 10;

    @Bean
    Scenario catalogueNaive(NaiveCatalogueService naive, Measurement measurement) {
        return new CatalogueScenario("catalogue-naive",
                "Books, then each book's author and subjects loaded lazily (N+1)", naive::list, null, measurement) {
            @Override
            protected void addToReport(ScenarioReport.Builder report) {
                Measured<List<BookSummary>> prolific = measurement.sql(() -> naive.listByAuthor("Prolific Author"));
                report.result("books of Prolific Author", prolific.value().size())
                        .result("books of Prolific Author: JDBC statements", prolific.sql().jdbcStatements())
                        .note("1 statement for the books + 1 per distinct author + 1 per book for its subjects.")
                        .note("Twenty books of one author: 1 + 1 author + 20 subject lists = 22, not 41. The "
                                + "persistence context answers the 19 repeated author lookups, so a test fixture "
                                + "with one shared author hides part of the N+1.");
            }
        };
    }

    @Bean
    Scenario catalogueJoinFetch(JoinFetchCatalogueService joinFetch, NaiveCatalogueService naive,
                                Measurement measurement) {
        return new CatalogueScenario("catalogue-join-fetch",
                "Books with their authors and subjects in one JOIN FETCH query", joinFetch::list, naive::list,
                measurement) {
            @Override
            protected void addToReport(ScenarioReport.Builder report) {
                report.note("One statement: the joins bring every author and subject with its book.")
                        .note("The rows still become managed entities (books, authors, subjects).");
            }
        };
    }

    @Bean
    Scenario catalogueProjection(ProjectionCatalogueService projection, NaiveCatalogueService naive,
                                 Measurement measurement) {
        return new CatalogueScenario("catalogue-projection",
                "Only the values the response needs, as records: no entities", projection::list, naive::list,
                measurement) {
            @Override
            protected void addToReport(ScenarioReport.Builder report) {
                report.note("One statement and no managed entities: nothing to register, snapshot or proxy.")
                        .note("The rows come flat (one per book and subject) and the service groups them.");
            }
        };
    }

    /** What every catalogue scenario does; each one adds its own lines and notes. */
    private abstract static class CatalogueScenario implements Scenario {

        private final String name;
        private final String description;
        private final Supplier<List<BookSummary>> catalogue;
        private final Supplier<List<BookSummary>> reference;
        protected final Measurement measurement;

        CatalogueScenario(String name, String description, Supplier<List<BookSummary>> catalogue,
                          Supplier<List<BookSummary>> reference, Measurement measurement) {
            this.name = name;
            this.description = description;
            this.catalogue = catalogue;
            this.reference = reference;
            this.measurement = measurement;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String lesson() {
            return "catalogue";
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public ScenarioReport run() {
            Measured<List<BookSummary>> measured = measurement.sql(catalogue);
            Timing timing = measurement.time(WARMUPS, SAMPLES, catalogue::get);
            ScenarioReport.Builder report = ScenarioReport.of(this)
                    .result("books", measured.value().size())
                    .sql("one call", measured.sql())
                    .result("time", timing.toString());
            if (reference != null) {
                // The naive reference sends hundreds of statements; with the log on they would be read as
                // part of this scenario.
                List<BookSummary> expected = measurement.withoutSqlLog(reference);
                report.result("same response as the naive version", measured.value().equals(expected));
            }
            addToReport(report);
            return report.build();
        }

        protected abstract void addToReport(ScenarioReport.Builder report);
    }
}
