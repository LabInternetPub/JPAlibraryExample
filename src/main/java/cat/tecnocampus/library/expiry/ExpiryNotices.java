package cat.tecnocampus.library.expiry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands for the e-mail a member receives when a reservation expires: it prints the notice on
 * the console. It also counts the notices per reservation, so a scenario can show a member who
 * was told twice, or told about a reservation that never expired.
 *
 * <p>A notice leaves the system: once printed (sent), a rollback cannot take it back.
 */
@Component
public class ExpiryNotices {

    private static final Logger log = LoggerFactory.getLogger(ExpiryNotices.class);

    private final Map<UUID, AtomicInteger> sent = new ConcurrentHashMap<>();

    public void send(ExpiryNotice notice) {
        log.info("Reservation {} expired: {} {}", notice.reservationId(), notice.memberEmail(), notice.barcodes());
        sent.computeIfAbsent(notice.reservationId(), id -> new AtomicInteger()).incrementAndGet();
    }

    /** How many notices were sent for this reservation. Exactly one per expired reservation is correct. */
    public int sentFor(UUID reservationId) {
        AtomicInteger count = sent.get(reservationId);
        return count == null ? 0 : count.get();
    }

    public int total() {
        return sent.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    /** Scenarios and tests start from zero. */
    public void clear() {
        sent.clear();
    }
}
