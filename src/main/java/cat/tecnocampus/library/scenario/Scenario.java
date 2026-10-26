package cat.tecnocampus.library.scenario;

/**
 * One demonstration: prepares its data, calls the naive or the better version of a use case,
 * measures it and returns a report. A naive scenario and its better counterpart measure the same
 * things, so their reports can be compared side by side.
 */
public interface Scenario {

    /** Unique name, used in {@code --scenario=name} and in {@code POST /scenarios/{name}}. */
    String name();

    /** The lesson it belongs to, e.g. "catalogue". */
    String lesson();

    String description();

    ScenarioReport run();
}
