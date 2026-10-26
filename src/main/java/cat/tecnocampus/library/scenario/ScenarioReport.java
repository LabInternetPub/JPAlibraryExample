package cat.tecnocampus.library.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a scenario measured, in the order it was measured, plus short notes for the class.
 * Returned as JSON by the controller and printed as text by the command-line runner.
 */
public record ScenarioReport(String scenario, String lesson, String description,
                             Map<String, Object> results, List<String> notes) {

    public static Builder of(Scenario scenario) {
        return new Builder(scenario);
    }

    public String toText() {
        int width = results.keySet().stream().mapToInt(String::length).max().orElse(0);
        StringBuilder text = new StringBuilder()
                .append("\n=== ").append(scenario).append(" (").append(lesson).append(") ===\n")
                .append(description).append('\n');
        results.forEach((key, value) -> text.append("  ").append(String.format("%-" + width + "s", key))
                .append(" : ").append(value).append('\n'));
        notes.forEach(note -> text.append("  * ").append(note).append('\n'));
        return text.toString();
    }

    public static final class Builder {

        private final Scenario scenario;
        private final Map<String, Object> results = new LinkedHashMap<>();
        private final List<String> notes = new ArrayList<>();

        private Builder(Scenario scenario) {
            this.scenario = scenario;
        }

        public Builder result(String key, Object value) {
            results.put(key, value);
            return this;
        }

        /** Adds the SQL counts with a prefix, e.g. "first import". */
        public Builder sql(String prefix, SqlCounts counts) {
            counts.asMap().forEach((key, value) -> results.put(prefix + ": " + key, value));
            return this;
        }

        public Builder note(String note) {
            notes.add(note);
            return this;
        }

        public ScenarioReport build() {
            return new ScenarioReport(scenario.name(), scenario.lesson(), scenario.description(),
                    new LinkedHashMap<>(results), List.copyOf(notes));
        }
    }
}
