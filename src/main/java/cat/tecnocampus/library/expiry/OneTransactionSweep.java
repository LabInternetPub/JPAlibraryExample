package cat.tecnocampus.library.expiry;

import cat.tecnocampus.library.persistence.ReservationRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * The sweep many first versions have (scenario S7): one method that is both the scheduled job and
 * the transaction, over every expired reservation at once. Typically written as
 * {@code @Scheduled(fixedRate = 60_000) @Transactional public void sweep()}; here it is not
 * scheduled, a scenario calls it.
 *
 * <p>It calls the same {@code expire} as the bounded sweep, but every call <em>joins</em> this
 * transaction (propagation {@code REQUIRED}) instead of starting its own:
 * <ul>
 *   <li>no page: it reads every candidate, however many accumulated;</li>
 *   <li>every reservation, copy and member stays in one persistence context until the end, and
 *       each auto-flush dirty-checks all of them (the import lesson's growth);</li>
 *   <li>each copy row it releases stays locked until the final commit: a member who wants one of
 *       those copies waits for the whole sweep;</li>
 *   <li>one failure rolls back everything, and the notices already sent stay sent.</li>
 * </ul>
 * With {@code fixedRate}, a run longer than the rate is followed at once by the next one.
 */
@Service
public class OneTransactionSweep {

    private final ReservationRepository reservations;
    private final NaiveExpiryTransitions transitions;
    private final Clock clock;

    public OneTransactionSweep(ReservationRepository reservations, NaiveExpiryTransitions transitions, Clock clock) {
        this.reservations = reservations;
        this.transitions = transitions;
        this.clock = clock;
    }

    @Transactional
    public ExpirySweep.SweepResult sweepAll() {
        List<UUID> candidates = reservations.findExpiredIds(clock.instant(), Pageable.unpaged());
        int expired = 0;
        for (UUID id : candidates) {
            if (transitions.expire(id)) {          // joins this transaction: nothing commits until the end
                expired++;
            }
        }
        return new ExpirySweep.SweepResult(candidates.size(), expired, 0);
    }
}
