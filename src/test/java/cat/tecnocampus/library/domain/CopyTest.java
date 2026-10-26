package cat.tecnocampus.library.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CopyTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    @Test
    void aNewCopyIsAvailable() {
        // Given / When
        Copy copy = Fixtures.copy();

        // Then
        assertThat(copy.getStatus()).isEqualTo(CopyStatus.AVAILABLE);
        assertThat(copy.isAvailableAt(NOW)).isTrue();
    }

    @Test
    void aReservedCopyIsNotAvailableBeforeItsDeadline() {
        // Given
        Copy copy = Fixtures.copy();
        copy.reserve(UUID.randomUUID(), NOW.plus(Duration.ofHours(48)), NOW);

        // When / Then
        assertThat(copy.isAvailableAt(NOW.plus(Duration.ofHours(47)))).isFalse();
        assertThat(copy.visibleStatusAt(NOW.plus(Duration.ofHours(47)))).isEqualTo(CopyStatus.RESERVED);
    }

    @Test
    void anExpiredReservationShowsAsAvailableWithoutAnyCleanup() {
        // Given
        Copy copy = Fixtures.copy();
        Instant deadline = NOW.plus(Duration.ofHours(48));
        copy.reserve(UUID.randomUUID(), deadline, NOW);

        // When: exactly at the deadline, nothing has changed the stored status
        boolean available = copy.isAvailableAt(deadline);

        // Then
        assertThat(available).isTrue();
        assertThat(copy.getStatus()).isEqualTo(CopyStatus.RESERVED);
        assertThat(copy.visibleStatusAt(deadline)).isEqualTo(CopyStatus.AVAILABLE);
    }

    @Test
    void anExpiredReservationCanBeReplacedByANewOne() {
        // Given
        Copy copy = Fixtures.copy();
        copy.reserve(UUID.randomUUID(), NOW.plus(Duration.ofHours(48)), NOW);
        Instant later = NOW.plus(Duration.ofDays(3));
        UUID second = UUID.randomUUID();

        // When
        copy.reserve(second, later.plus(Duration.ofHours(48)), later);

        // Then
        assertThat(copy.getReservationId()).isEqualTo(second);
    }

    @Test
    void reservingAReservedCopyIsRefused() {
        // Given
        Copy copy = Fixtures.copy();
        copy.reserve(UUID.randomUUID(), NOW.plus(Duration.ofHours(48)), NOW);

        // When / Then
        assertThatThrownBy(() -> copy.reserve(UUID.randomUUID(), NOW.plus(Duration.ofHours(49)), NOW.plusSeconds(60)))
                .isInstanceOf(CopiesUnavailableException.class);
    }

    @Test
    void copiesOnLoanOrWithdrawnAreNotAvailable() {
        // Given
        Copy onLoan = Fixtures.copy();
        onLoan.lend(NOW);
        Copy withdrawn = Fixtures.copy();
        withdrawn.withdraw();

        // When / Then
        assertThat(onLoan.isAvailableAt(NOW)).isFalse();
        assertThat(withdrawn.isAvailableAt(NOW)).isFalse();
        assertThatThrownBy(() -> withdrawn.reserve(UUID.randomUUID(), NOW.plusSeconds(60), NOW))
                .isInstanceOf(CopiesUnavailableException.class);
    }

    @Test
    void reimportChangesOnlyPhysicalAttributesAndReportsRealChanges() {
        // Given
        Copy copy = Fixtures.copy();
        UUID id = copy.getId();
        copy.reserve(UUID.randomUUID(), NOW.plusSeconds(60), NOW);

        // When
        boolean unchanged = copy.reimport(1, "1-A", Condition.GOOD);
        boolean changed = copy.reimport(2, "2-C", Condition.WORN);

        // Then
        assertThat(unchanged).isFalse();
        assertThat(changed).isTrue();
        assertThat(copy.getId()).isEqualTo(id);
        assertThat(copy.getFloor()).isEqualTo(2);
        assertThat(copy.getShelf()).isEqualTo("2-C");
        assertThat(copy.getCondition()).isEqualTo(Condition.WORN);
        assertThat(copy.getStatus()).isEqualTo(CopyStatus.RESERVED);
    }
}
