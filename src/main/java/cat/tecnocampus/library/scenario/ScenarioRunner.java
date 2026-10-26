package cat.tecnocampus.library.scenario;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Runs the scenarios named on the command line, prints their reports and stops the application:
 * {@code ./mvnw spring-boot:run -Dspring-boot.run.arguments=--scenario=catalogue-naive,catalogue-join-fetch}.
 * Without {@code --scenario} it does nothing and the application keeps running for scenarios.http.
 */
@Component
public class ScenarioRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunner.class);

    private final ScenarioRegistry registry;
    private final ConfigurableApplicationContext context;
    private final String requested;

    public ScenarioRunner(ScenarioRegistry registry, ConfigurableApplicationContext context,
                          @Value("${scenario:}") String requested) {
        this.registry = registry;
        this.context = context;
        this.requested = requested;
    }

    @Override
    public void run(String... args) {
        if (requested.isBlank()) {
            return;
        }
        Arrays.stream(requested.split(","))
                .map(String::trim)
                .forEach(name -> registry.find(name).ifPresentOrElse(
                        scenario -> log.info(scenario.run().toText()),
                        () -> log.error("Unknown scenario '{}'. Known: {}", name,
                                registry.all().stream().map(Scenario::name).toList())));
        System.exit(SpringApplication.exit(context));
    }
}
