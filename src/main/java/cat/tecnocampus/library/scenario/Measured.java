package cat.tecnocampus.library.scenario;

/** The result of an action together with what Hibernate did while it ran. */
public record Measured<T>(T value, SqlCounts sql) {
}
