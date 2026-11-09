package cat.tecnocampus.library.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Collecting a reserved copy, and giving it back when its reservation ends. */
class CopyCollectTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final Instant DEADLINE = NOW.plus(Duration.ofHours(48));

    private final UUID anna = UUID.randomUUID();
    private final UUID bruno = UUID.randomUUID();
    private final Copy copy = Fixtures.copy();

    @Test
    void theReservationThatHoldsTheCopyCollectsItAndTheDeadlineDisappears() {
        // Given
        copy.reserve(anna, DEADLINE, NOW);

        // When
        copy.collect(anna, DEADLINE.minusNanos(1));

        // Then
        assertThat(copy.getStatus()).isEqualTo(CopyStatus.ON_LOAN);
        assertThat(copy.getReservationId()).isNull();
        assertThat(copy.getReservedUntil()).isNull();
        assertThat(copy.isAvailableAt(DEADLINE.plus(Duration.ofDays(1)))).isFalse();
    }

    @Test
    void lendingIsNotCollecting() {
        // Given
        copy.reserve(anna, DEADLINE, NOW);

        // When / Then: lend refuses a reserved copy, even before the reservation's deadline
        assertThatThrownBy(() -> copy.lend(NOW.plusSeconds(60)))
                .isInstanceOf(CopiesUnavailableException.class);
    }

    @Test
    void aCopyCannotBeCollectedAtItsDeadlineOrByAnotherReservation() {
        // Given
        copy.reserve(anna, DEADLINE, NOW);

        // When / Then
        assertThatThrownBy(() -> copy.collect(anna, DEADLINE))
                .isInstanceOf(CopiesUnavailableException.class);
        assertThatThrownBy(() -> copy.collect(bruno, NOW.plusSeconds(60)))
                .isInstanceOf(CopiesUnavailableException.class);
        assertThat(copy.getStatus()).isEqualTo(CopyStatus.RESERVED);
        assertThat(copy.getReservationId()).isEqualTo(anna);
    }

    @Test
    void anAvailableCopyCannotBeCollected() {
        // When / Then: e.g. the reservation was expired and the copy released meanwhile
        assertThatThrownBy(() -> copy.collect(anna, NOW))
                .isInstanceOf(CopiesUnavailableException.class);
    }

    @Test
    void releasingGivesTheCopyBackWhenTheReservationStillHoldsIt() {
        // Given
        copy.reserve(anna, DEADLINE, NOW);

        // When
        boolean released = copy.releaseFrom(anna);

        // Then
        assertThat(released).isTrue();
        assertThat(copy.getStatus()).isEqualTo(CopyStatus.AVAILABLE);
        assertThat(copy.getReservationId()).isNull();
        assertThat(copy.getReservedUntil()).isNull();
    }

    @Test
    void aLateReleaseDoesNotFreeACopyThatAnotherReservationHasTaken() {
        // Given: Anna's reservation expired, nobody swept, and Bruno reserved the copy
        copy.reserve(anna, DEADLINE, NOW);
        Instant later = DEADLINE.plus(Duration.ofMinutes(5));
        copy.reserve(bruno, later.plus(Duration.ofHours(48)), later);

        // When: the sweep finally releases Anna's reservation
        boolean released = copy.releaseFrom(anna);

        // Then
        assertThat(released).isFalse();
        assertThat(copy.getStatus()).isEqualTo(CopyStatus.RESERVED);
        assertThat(copy.getReservationId()).isEqualTo(bruno);
    }

    @Test
    void releasingACopyOnLoanChangesNothing() {
        // Given
        copy.reserve(anna, DEADLINE, NOW);
        copy.collect(anna, NOW.plusSeconds(60));

        // When
        boolean released = copy.releaseFrom(anna);

        // Then
        assertThat(released).isFalse();
        assertThat(copy.getStatus()).isEqualTo(CopyStatus.ON_LOAN);
    }
}
