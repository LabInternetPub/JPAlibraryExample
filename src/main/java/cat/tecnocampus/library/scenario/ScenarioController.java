package cat.tecnocampus.library.scenario;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Runs scenarios from scenarios.http while the application is running. */
@RestController
@RequestMapping("/scenarios")
public class ScenarioController {

    private final ScenarioRegistry registry;

    public ScenarioController(ScenarioRegistry registry) {
        this.registry = registry;
    }

    public record ScenarioInfo(String name, String lesson, String description) {
    }

    @GetMapping
    public List<ScenarioInfo> list() {
        return registry.all().stream()
                .map(s -> new ScenarioInfo(s.name(), s.lesson(), s.description()))
                .toList();
    }

    @PostMapping("/{name}")
    public ScenarioReport run(@PathVariable String name) {
        Scenario scenario = registry.find(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown scenario: " + name));
        return scenario.run();
    }
}
