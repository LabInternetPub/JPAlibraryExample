package cat.tecnocampus.library.scenario;

import java.util.function.Function;

/** A scenario defined by its name, lesson, description and a body that builds the report. */
public record SimpleScenario(String name, String lesson, String description,
                             Function<Scenario, ScenarioReport> body) implements Scenario {

    @Override
    public ScenarioReport run() {
        return body.apply(this);
    }
}
