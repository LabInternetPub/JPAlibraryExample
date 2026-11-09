package cat.tecnocampus.library;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One clock for "now": expiry is decided with the request's time, and scenarios and tests can
 * move it. Declared as {@link MovableClock}, so it is injected both where a {@code Clock} is asked
 * for and where a scenario needs to move it.
 */
@Configuration
class ClockConfiguration {

    @Bean
    MovableClock clock() {
        return new MovableClock();
    }
}
