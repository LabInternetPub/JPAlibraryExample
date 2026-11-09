package cat.tecnocampus.library.expiry;

import cat.tecnocampus.library.persistence.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One run of the expiry sweep: a bounded page of candidates, oldest deadline first, each one
 * expired in its own transaction by the {@link ReservationExpirer} (another bean, so its
 * {@code @Transactional} proxy applies). A failure affects one reservation; whatever does not fit
 * in the page is left for the next run.
 *
 * <p>Not a bean: the scheduler builds one, and the scenarios and tests build their own with the
 * naive or the better transitions and call {@link #sweepOnce()} at the moment they choose.
 *
 * <p>Whether the result is correct does not depend on this class: it depends on the expirer.
 */
public class ExpirySweep {

    public static final int PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(ExpirySweep.class);

    private final ReservationRepository reservations;
    private final ReservationExpirer expirer;
    private final Clock clock;
    private final int pageSize;

    public ExpirySweep(ReservationRepository reservations, ReservationExpirer expirer, Clock clock) {
        this(reservations, expirer, clock, PAGE_SIZE);
    }

    public ExpirySweep(ReservationRepository reservations, ReservationExpirer expirer, Clock clock, int pageSize) {
        this.reservations = Objects.requireNonNull(reservations);
        this.expirer = Objects.requireNonNull(expirer);
        this.clock = Objects.requireNonNull(clock);
        this.pageSize = pageSize;
    }

    public SweepResult sweepOnce() {
        List<UUID> candidates = reservations.findExpiredIds(clock.instant(), PageRequest.of(0, pageSize));
        int expired = 0;
        int failed = 0;
        for (UUID id : candidates) {
            try {
                if (expirer.expire(id)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                // Its transaction rolled back; it is still a candidate for the next run.
                failed++;
                log.warn("Could not expire reservation {}: {}", id, e.toString());
            }
        }
        return new SweepResult(candidates.size(), expired, failed);
    }

    /**
     * @param candidates reservations the run looked at (at most the page size)
     * @param expired    how many this run expired
     * @param failed     how many threw; they stay candidates
     */
    public record SweepResult(int candidates, int expired, int failed) {
    }
}
