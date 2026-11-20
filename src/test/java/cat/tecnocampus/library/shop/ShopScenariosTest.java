package cat.tecnocampus.library.shop;

import cat.tecnocampus.library.GatewayContainerConfiguration;
import cat.tecnocampus.library.TestcontainersConfiguration;
import cat.tecnocampus.library.scenario.ScenarioReport;
import cat.tecnocampus.library.scenario.ScenarioRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pool scenarios, run as in class: the naive purchase exhausts the connection pool and an
 * unrelated catalogue read cannot answer in time; the better one leaves the pool free.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, GatewayContainerConfiguration.class})
class ShopScenariosTest {

    @Autowired ScenarioRegistry scenarios;

    @Test
    void theNaivePurchaseStarvesThePoolAndTheBetterOneDoesNot() {
        // When
        ScenarioReport naive = scenarios.find("purchase-pool-naive").orElseThrow().run();
        ScenarioReport better = scenarios.find("purchase-pool-better").orElseThrow().run();

        // Then
        // every connection busy and requests queueing (fewer than 60 active only while the pool is still opening them)
        assertThat((String) naive.results().get("pool while the purchases wait for the provider"))
                .matches("\\d+ active, 0 idle, [1-9]\\d* threads waiting for one");
        assertThat((String) naive.results().get("unrelated catalogue read at that moment")).startsWith("NO ANSWER");

        assertThat((String) better.results().get("pool while the purchases wait for the provider")).startsWith("0 active");
        assertThat((String) better.results().get("unrelated catalogue read at that moment")).contains("321 books");
        assertThat(answers(better)).containsExactly(Map.entry("201 PAID", 80L));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> answers(ScenarioReport report) {
        return (Map<String, Long>) report.results().get("answers");
    }
}
