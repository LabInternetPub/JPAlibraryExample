package cat.tecnocampus.library.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

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
        loan.giveBack();

        // Then
        assertThat(loan.isOpen()).isFalse();
        assertThat(copy.getStatus()).isEqualTo(CopyStatus.AVAILABLE);
        assertThatThrownBy(loan::giveBack).isInstanceOf(IllegalStateException.class);
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
