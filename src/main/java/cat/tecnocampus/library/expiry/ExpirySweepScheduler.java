package cat.tecnocampus.library.expiry;

import cat.tecnocampus.library.persistence.ReservationRepository;
import cat.tecnocampus.library.scenario.TransitionPauses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * The scheduled process of the requirements: every {@code library.expiry.sweep-delay} ms, one
 * {@link ExpirySweep} run with the naive transitions (stage 2 of the lesson; the better ones
 * replace them later).
 *
 * <p>{@code fixedDelay}: the delay counts from the end of the previous run, so a slow run is never
 * followed immediately by the next one. It prevents overlap inside this scheduler only: two
 * application instances each have their own.
 *
 * <p>Off unless {@code library.expiry.sweep-enabled=true}. Central's example data includes 100
 * reservations that have already expired but are still stored as {@code RESERVED}: the shelf-map
 * lesson shows them as available without any cleanup, and a sweep running in the background
 * would erase that example a minute after the start. Tests and scenarios call the sweep themselves,
 * at the moment they choose.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "library.expiry.sweep-enabled", havingValue = "true")
public class ExpirySweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpirySweepScheduler.class);

    private final ExpirySweep sweep;

    public ExpirySweepScheduler(ReservationRepository reservations, NaiveExpiryTransitions transitions,
                                TransitionPauses pauses, Clock clock) {
        this.sweep = new ExpirySweep(reservations, transitions, clock, ExpirySweep.PAGE_SIZE, pauses);
    }

    @Scheduled(fixedDelayString = "${library.expiry.sweep-delay:60000}",
               initialDelayString = "${library.expiry.sweep-delay:60000}")
    public void sweep() {
        ExpirySweep.SweepResult result = sweep.sweepOnce();
        if (result.candidates() > 0) {
            log.info("Expiry sweep: {}", result);
        }
    }
}
