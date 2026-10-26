package cat.tecnocampus.library.scenario;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Every {@link Scenario} bean of the application, by name. */
@Component
public class ScenarioRegistry {

    private final Map<String, Scenario> scenarios;

    public ScenarioRegistry(List<Scenario> scenarios) {
        this.scenarios = scenarios.stream().collect(Collectors.toMap(Scenario::name, Function.identity()));
    }

    public Optional<Scenario> find(String name) {
        return Optional.ofNullable(scenarios.get(name));
    }

    public List<Scenario> all() {
        return scenarios.values().stream()
                .sorted(Comparator.comparing(Scenario::lesson).thenComparing(Scenario::name))
                .toList();
    }
}
