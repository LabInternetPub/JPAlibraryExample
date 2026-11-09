package cat.tecnocampus.library;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * The application's clock: the system clock, unless a scenario or a test moves it.
 *
 * <p>Reservations live 48 hours. No scenario and no test can wait that long, so every "now" in
 * the application comes from this bean, and a scenario can {@link #fixAt(Instant) stop it} at an
 * exact instant (one nanosecond before a deadline, say) or {@link #advance(Duration) move it}.
 *
 * <p>Learning-environment simplification: a production application would inject a plain
 * {@code Clock.systemUTC()} and let only the tests replace it. Here the scenarios run inside the
 * application, so the movable clock is the application's clock. Untouched, it is the system clock.
 *
 * <p>Thread-safe: concurrency scenarios move the clock while other threads read it.
 */
public final class MovableClock extends Clock {

    private final Clock system;
    private Instant fixed;                 // null: follow the system clock
    private Duration offset = Duration.ZERO;

    public MovableClock() {
        this(Clock.systemUTC());
    }

    public MovableClock(Clock system) {
        this.system = Objects.requireNonNull(system);
    }

    /**
     * Truncated to microseconds, the precision of a PostgreSQL {@code timestamp}. The time rule is
     * applied both in Java ({@code now < expiresAt}) and in SQL ({@code expires_at <= :now}); with
     * nanoseconds, the driver rounds {@code deadline − 1 ns} up to the deadline, and the two would
     * disagree about the same instant. It also keeps a stored {@code expiresAt} equal to the one
     * the entity had in memory.
     */
    @Override
    public synchronized Instant instant() {
        Instant now = fixed != null ? fixed : system.instant().plus(offset);
        return now.truncatedTo(ChronoUnit.MICROS);
    }

    /** Stops the clock at this instant: every call to {@link #instant()} returns it until moved. */
    public synchronized void fixAt(Instant instant) {
        fixed = Objects.requireNonNull(instant);
    }

    /** Moves the clock forward (or back, with a negative duration), stopped or running. */
    public synchronized void advance(Duration duration) {
        Objects.requireNonNull(duration);
        if (fixed != null) {
            fixed = fixed.plus(duration);
        } else {
            offset = offset.plus(duration);
        }
    }

    /** Back to the system clock, running, with no offset. */
    public synchronized void reset() {
        fixed = null;
        offset = Duration.ZERO;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** Only UTC: the application stores and compares instants, never local times. */
    @Override
    public Clock withZone(ZoneId zone) {
        if (!ZoneOffset.UTC.equals(zone.normalized())) {
            throw new UnsupportedOperationException("The application clock is always UTC");
        }
        return this;
    }
}
