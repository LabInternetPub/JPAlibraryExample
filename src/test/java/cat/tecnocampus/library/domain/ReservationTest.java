package cat.tecnocampus.library.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    @Test
    void aReservationLastsFortyEightHours() {
        // Given
        Copy copy = Fixtures.copy();

        // When
        Reservation reservation = new Reservation(Fixtures.member(), List.of(copy.getId()), NOW);

        // Then
        assertThat(reservation.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(48)));
        assertThat(reservation.getQuantity()).isEqualTo(1);
        assertThat(reservation.isLiveAt(NOW.plus(Duration.ofHours(47)))).isTrue();
        assertThat(reservation.isLiveAt(NOW.plus(Duration.ofHours(48)))).isFalse();
    }

    @Test
    void reserveHoldsEveryCopyUntilTheReservationExpires() {
        // Given
        Copy first = Fixtures.copy();
        Copy second = Fixtures.copy();
        Reservation reservation = new Reservation(Fixtures.member(), List.of(first.getId(), second.getId()), NOW);

        // When
        reservation.reserve(List.of(first, second), NOW);

        // Then
        assertThat(List.of(first, second)).allSatisfy(copy -> {
            assertThat(copy.getStatus()).isEqualTo(CopyStatus.RESERVED);
            assertThat(copy.getReservationId()).isEqualTo(reservation.getId());
            assertThat(copy.getReservedUntil()).isEqualTo(reservation.getExpiresAt());
        });
    }

    @Test
    void checkAvailableListsEveryUnavailableAndMissingCopy() {
        // Given
        Copy free = Fixtures.copy();
        Copy taken = Fixtures.copy();
        taken.lend(NOW);
        UUID missing = UUID.randomUUID();

        // When / Then
        assertThatThrownBy(() -> Reservation.checkAvailable(
                List.of(free.getId(), taken.getId(), missing), List.of(free, taken), NOW))
                .isInstanceOfSatisfying(CopiesUnavailableException.class,
                        e -> assertThat(e.getCopyIds()).containsExactly(taken.getId(), missing));
    }

    @Test
    void checkAvailableAcceptsCopiesThatAreAllAvailable() {
        // Given
        Copy first = Fixtures.copy();
        Copy second = Fixtures.copy();

        // When / Then: no exception
        Reservation.checkAvailable(List.of(first.getId(), second.getId()), List.of(first, second), NOW);
    }

    @Test
    void aReservationNeedsDistinctCopies() {
        // Given
        UUID copy = UUID.randomUUID();

        // When / Then
        assertThatThrownBy(() -> new Reservation(Fixtures.member(), List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Reservation(Fixtures.member(), List.of(copy, copy), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reserveRefusesCopiesThatAreNotTheRequestedOnes() {
        // Given
        Copy requested = Fixtures.copy();
        Copy other = Fixtures.copy();
        Reservation reservation = new Reservation(Fixtures.member(), List.of(requested.getId()), NOW);

        // When / Then
        assertThatThrownBy(() -> reservation.reserve(List.of(other), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
