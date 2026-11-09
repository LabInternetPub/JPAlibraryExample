package cat.tecnocampus.library.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The time rule of the expiry lesson, with no Spring and no database: if this needed a database,
 * the rule would be in the wrong layer. The boundary is tested on both sides of the deadline.
 */
class ReservationExpiryTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    private final Member anna = Fixtures.member();
    private final Reservation reservation = new Reservation(anna, List.of(UUID.randomUUID()), NOW);
    private final Instant deadline = reservation.getExpiresAt();
    private final Instant justBefore = deadline.minusNanos(1);

    @Test
    void oneNanosecondBeforeTheDeadlineItCanBeCollectedButNotExpired() {
        // When
        boolean expired = reservation.expire(justBefore);
        reservation.collect(justBefore);

        // Then
        assertThat(expired).isFalse();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.COLLECTED);
    }

    @Test
    void exactlyAtTheDeadlineItCanBeExpiredButNotCollected() {
        // When / Then
        assertThat(reservation.isLiveAt(deadline)).isFalse();
        assertThatThrownBy(() -> reservation.collect(deadline))
                .isInstanceOf(ReservationNotActiveException.class);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);

        assertThat(reservation.expire(deadline)).isTrue();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void onlyTheFirstExpiryReportsThatItMustReleaseTheCopies() {
        // Given
        reservation.expire(deadline);

        // When
        boolean second = reservation.expire(deadline.plusSeconds(60));

        // Then
        assertThat(second).isFalse();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void aCollectedReservationNeverExpiresNorCanBeCancelled() {
        // Given
        reservation.collect(justBefore);

        // When / Then
        assertThat(reservation.expire(deadline.plusSeconds(3600))).isFalse();
        assertThatThrownBy(() -> reservation.cancel(anna.getId(), justBefore))
                .isInstanceOf(ReservationNotActiveException.class);
        assertThatThrownBy(() -> reservation.collect(justBefore))
                .isInstanceOf(ReservationNotActiveException.class);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.COLLECTED);
    }

    @Test
    void anExpiredReservationCannotBeCollectedOrCancelled() {
        // Given
        reservation.expire(deadline);

        // When / Then: even with a "now" from before the deadline (a stale clock reading)
        assertThatThrownBy(() -> reservation.collect(justBefore))
                .isInstanceOf(ReservationNotActiveException.class);
        assertThatThrownBy(() -> reservation.cancel(anna.getId(), justBefore))
                .isInstanceOf(ReservationNotActiveException.class);
    }

    @Test
    void theMemberCanCancelBeforeTheDeadlineOnly() {
        // Given
        Reservation other = new Reservation(anna, List.of(UUID.randomUUID()), NOW);

        // When
        reservation.cancel(anna.getId(), justBefore);

        // Then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.expire(deadline)).isFalse();
        assertThatThrownBy(() -> other.cancel(anna.getId(), deadline))
                .isInstanceOf(ReservationNotActiveException.class);
    }

    @Test
    void anotherMembersReservationIsNotFound() {
        // When / Then
        assertThatThrownBy(() -> reservation.cancel(UUID.randomUUID(), NOW))
                .isInstanceOf(NotFoundException.class);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    }
}
