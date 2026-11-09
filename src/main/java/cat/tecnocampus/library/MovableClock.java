package cat.tecnocampus.library;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

    @Override
    public synchronized Instant instant() {
        return fixed != null ? fixed : system.instant().plus(offset);
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
