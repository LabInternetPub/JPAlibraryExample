package cat.tecnocampus.library.loan;

import cat.tecnocampus.library.domain.Loan;

import java.time.Instant;
import java.util.UUID;

public record LoanResponse(UUID id, Instant dueAt, boolean returned, Instant returnedAt) {

    static LoanResponse from(Loan loan) {
        return new LoanResponse(loan.getId(), loan.getDueAt(), loan.isReturned(), loan.getReturnedAt());
    }
}
