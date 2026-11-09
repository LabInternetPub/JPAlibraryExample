package cat.tecnocampus.library;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MovableClockTest {

    private static final Instant T = Instant.parse("2026-11-02T10:00:00Z");

    /** A "system" clock the test controls, so the running mode can be checked exactly. */
    private final MovableClock system = new MovableClock(Clock.fixed(T, ZoneOffset.UTC));
    private final MovableClock clock = new MovableClock(system);

    @Test
    void untouchedItIsTheSystemClock() {
        assertThat(clock.instant()).isEqualTo(T);
        system.advance(Duration.ofSeconds(5));
        assertThat(clock.instant()).isEqualTo(T.plusSeconds(5));
    }

    @Test
    void fixedItStopsAtThatInstantAndMovesOnlyWhenAdvanced() {
        // Given
        Instant deadline = T.plus(Duration.ofHours(48));

        // When
        clock.fixAt(deadline.minusNanos(1));
        system.advance(Duration.ofSeconds(5));

        // Then: the last microsecond before the deadline, then the deadline
        assertThat(clock.instant()).isEqualTo(deadline.minus(1, ChronoUnit.MICROS));
        clock.advance(Duration.ofNanos(1));
        assertThat(clock.instant()).isEqualTo(deadline);
    }

    @Test
    void itHasTheDatabasesPrecisionTruncatedNeverRoundedUp() {
        // Given: a system clock with nanoseconds, as on Linux
        MovableClock nanos = new MovableClock(Clock.fixed(T.plusNanos(999_999_999), ZoneOffset.UTC));

        // When / Then: PostgreSQL keeps microseconds; rounding up would cross into the next second
        assertThat(nanos.instant()).isEqualTo(T.plusNanos(999_999_000));
    }

    @Test
    void runningItKeepsTheOffsetItWasAdvancedBy() {
        // When
        clock.advance(Duration.ofHours(48));
        system.advance(Duration.ofSeconds(1));

        // Then
        assertThat(clock.instant()).isEqualTo(T.plus(Duration.ofHours(48)).plusSeconds(1));
    }

    @Test
    void resetGoesBackToTheSystemClock() {
        // Given
        clock.fixAt(T.plus(Duration.ofDays(3)));
        clock.advance(Duration.ofHours(1));

        // When
        clock.reset();

        // Then
        assertThat(clock.instant()).isEqualTo(T);
    }

    @Test
    void itIsAlwaysUtc() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(clock.withZone(ZoneId.of("Z"))).isSameAs(clock);
        assertThatThrownBy(() -> clock.withZone(ZoneId.of("Europe/Madrid")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
