package cat.tecnocampus.library.shelfmap;

import cat.tecnocampus.library.domain.CopyStatus;
import cat.tecnocampus.library.persistence.ReservationRepository;
import cat.tecnocampus.library.scenario.Measured;
import cat.tecnocampus.library.scenario.Measurement;
import cat.tecnocampus.library.scenario.Scenario;
import cat.tecnocampus.library.scenario.ScenarioReport;
import cat.tecnocampus.library.scenario.Timing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The two shelf-map scenarios on Central (20,000 copies). Both measure the same things: SQL of
 * one call, time and peak heap of call plus JSON serialization (the response size is part of the
 * cost), bytes of the JSON, the statuses it shows and whether it leaks a reservation.
 */
@Configuration
class ShelfMapScenarios {

    private static final String BRANCH = "Central";
    private static final int WARMUPS = 3;
    private static final int SAMPLES = 10;

    @Bean
    Scenario shelfMapNaive(NaiveShelfMapService naive, Measurement measurement, ObjectMapper json,
                           ReservationRepository reservations) {
        return new ShelfMapScenario<>("shelf-map-naive",
                "Copies and books as managed entities, one JSON object per copy",
                () -> naive.get(BRANCH), ShelfMapScenarios::statuses, null, measurement, json, reservations) {
            @Override
            protected void addNotes(ScenarioReport.Builder report) {
                report.note("Two statements: the SQL count says nothing is wrong.")
                        .note("20,300 managed entities (copies and books), then 20,000 DTOs: three layers of objects.")
                        .note("Every copy repeats the same field names in the JSON.");
            }
        };
    }

    @Bean
    Scenario shelfMapProjection(ProjectedShelfMapService projected, NaiveShelfMapService naive,
                                Measurement measurement, ObjectMapper json, ReservationRepository reservations) {
        return new ShelfMapScenario<>("shelf-map-projection",
                "Scalars from one query, grouped by shelf with parallel arrays",
                () -> projected.get(BRANCH), ShelfMapResponse::statusesByCopy,
                () -> statuses(naive.get(BRANCH)), measurement, json, reservations) {
            @Override
            protected void addNotes(ScenarioReport.Builder report) {
                report.note("No copy or book entities (the 1 loaded is the branch): the query selects only what the map shows.")
                        .note("Expiry is decided in the query; the deadline is used but never selected.")
                        .note("Floor and shelf once per shelf, one letter per status; most bytes left are the copy ids.");
            }
        };
    }

    private static Map<UUID, CopyStatus> statuses(NaiveShelfMap map) {
        return map.copies().stream().collect(Collectors.toMap(CopyItem::id, CopyItem::status));
    }

    private abstract static class ShelfMapScenario<T> implements Scenario {

        private final String name;
        private final String description;
        private final Supplier<T> map;
        private final Function<T, Map<UUID, CopyStatus>> statuses;
        private final Supplier<Map<UUID, CopyStatus>> reference;
        private final Measurement measurement;
        private final ObjectMapper json;
        private final ReservationRepository reservations;

        ShelfMapScenario(String name, String description, Supplier<T> map, Function<T, Map<UUID, CopyStatus>> statuses,
                         Supplier<Map<UUID, CopyStatus>> reference, Measurement measurement, ObjectMapper json,
                         ReservationRepository reservations) {
            this.name = name;
            this.description = description;
            this.map = map;
            this.statuses = statuses;
            this.reference = reference;
            this.measurement = measurement;
            this.json = json;
            this.reservations = reservations;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String lesson() {
            return "shelf-map";
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public ScenarioReport run() {
            Measured<T> measured = measurement.sql(map);
            byte[] body = json.writeValueAsBytes(measured.value());
            Timing timing = measurement.time(WARMUPS, SAMPLES, () -> json.writeValueAsBytes(map.get()));
            // The heap run, the leak check and the reference send SQL of their own; with the log on it
            // would be read as part of the measured call. Only the first call is counted and logged.
            Measurement.HeapUse heap = measurement.withoutSqlLog(
                    () -> measurement.heap(() -> json.writeValueAsBytes(map.get())));

            Map<UUID, CopyStatus> shown = statuses.apply(measured.value());
            Map<CopyStatus, Long> byStatus = new TreeMap<>(shown.values().stream()
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting())));

            ScenarioReport.Builder report = ScenarioReport.of(this)
                    .result("copies", shown.size())
                    .result("statuses shown", byStatus)
                    .sql("one call", measured.sql())
                    .result("JSON bytes", body.length)
                    .result("time (service + JSON)", timing.toString())
                    .result("peak heap growth (service + JSON)", heap.peakIncrease())
                    .result("reservation ids or deadlines in the JSON", leaks(body));
            if (reference != null) {
                Map<UUID, CopyStatus> expected = measurement.withoutSqlLog(reference);
                report.result("same copies and statuses as the naive version", shown.equals(expected));
            }
            addNotes(report);
            report.note("100 of the 500 reservations have expired: they are shown as AVAILABLE without any cleanup.");
            return report.build();
        }

        private long leaks(byte[] body) {
            String text = new String(body, StandardCharsets.UTF_8);
            List<UUID> ids = measurement.withoutSqlLog(reservations::findAllIds);
            long leakedIds = ids.stream().filter(id -> text.contains(id.toString())).count();
            boolean leakedFields = text.contains("reservationId") || text.contains("reservedUntil");
            return leakedIds + (leakedFields ? 1 : 0);
        }

        protected abstract void addNotes(ScenarioReport.Builder report);
    }
}
