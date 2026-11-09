package cat.tecnocampus.library.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    @Test
    void lendingPutsTheCopyOnLoanAndReturningPutsItBack() {
        // Given
        Copy copy = Fixtures.copy();

        // When
        Loan loan = new Loan(Fixtures.member(), copy, NOW);

        // Then
        assertThat(loan.isOpen()).isTrue();
        assertThat(loan.getDueAt()).isEqualTo(NOW.plus(Loan.DURATION));
        assertThat(copy.getStatus()).isEqualTo(CopyStatus.ON_LOAN);

        // When
        boolean closed = loan.giveBack(NOW.plusSeconds(60));

        // Then
        assertThat(closed).isTrue();
        assertThat(loan.isOpen()).isFalse();
        assertThat(loan.getReturnedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(copy.getStatus()).isEqualTo(CopyStatus.AVAILABLE);
    }

    @Test
    void aRetriedReturnIsNotAnErrorAndHasNoSecondEffect() {
        // Given: returned, and the copy reserved by someone else since
        Copy copy = Fixtures.copy();
        Loan loan = new Loan(Fixtures.member(), copy, NOW);
        loan.giveBack(NOW.plusSeconds(60));
        UUID bruno = UUID.randomUUID();
        copy.reserve(bruno, NOW.plus(Duration.ofHours(48)), NOW.plusSeconds(90));

        // When: the return machine retries
        boolean closedAgain = loan.giveBack(NOW.plusSeconds(120));

        // Then
        assertThat(closedAgain).isFalse();
        assertThat(loan.getReturnedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(copy.getStatus()).isEqualTo(CopyStatus.RESERVED);
        assertThat(copy.getReservationId()).isEqualTo(bruno);
    }

    @Test
    void aLoanForACollectionRecordsTheCopyWithoutLendingItAgain() {
        // Given: the copy already went on loan through its reservation
        Copy copy = Fixtures.copy();
        UUID reservation = UUID.randomUUID();
        copy.reserve(reservation, NOW.plus(Duration.ofHours(48)), NOW);
        copy.collect(reservation, NOW.plusSeconds(60));

        // When
        Loan loan = Loan.forCollection(Fixtures.member(), copy, NOW.plusSeconds(60));

        // Then
        assertThat(loan.isOpen()).isTrue();
        assertThat(loan.getCopy()).isSameAs(copy);
        assertThat(loan.getDueAt()).isEqualTo(NOW.plusSeconds(60).plus(Loan.DURATION));
        assertThat(copy.getStatus()).isEqualTo(CopyStatus.ON_LOAN);
    }

    @Test
    void aCopyOnLoanCannotBeLentAgain() {
        // Given
        Copy copy = Fixtures.copy();
        new Loan(Fixtures.member(), copy, NOW);

        // When / Then
        assertThatThrownBy(() -> new Loan(Fixtures.member(), copy, NOW))
                .isInstanceOf(CopiesUnavailableException.class);
    }
}
