package cat.tecnocampus.library.scenario;

import cat.tecnocampus.library.MovableClock;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/**
 * Moves the application's clock from scenarios.http, to show expiry live without waiting 48 hours:
 * reserve, move the clock, read the reservation (410) or run the sweep. Demo only.
 */
@RestController
@RequestMapping("/demo/clock")
public class DemoClockController {

    private final MovableClock clock;

    public DemoClockController(MovableClock clock) {
        this.clock = clock;
    }

    public record Now(Instant now) {
    }

    @GetMapping
    public Now now() {
        return new Now(clock.instant());
    }

    /** Moves the clock forward, e.g. {@code POST /demo/clock?plusHours=48}. It keeps running. */
    @PostMapping
    public Now advance(@RequestParam(defaultValue = "0") long plusHours,
                       @RequestParam(defaultValue = "0") long plusMinutes) {
        clock.advance(Duration.ofHours(plusHours).plusMinutes(plusMinutes));
        return now();
    }

    /** Back to the system clock. */
    @DeleteMapping
    public Now reset() {
        clock.reset();
        return now();
    }
}
