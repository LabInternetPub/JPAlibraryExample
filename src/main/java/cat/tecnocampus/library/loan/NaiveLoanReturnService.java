package cat.tecnocampus.library.loan;

import cat.tecnocampus.library.domain.Loan;
import cat.tecnocampus.library.domain.NotFoundException;
import cat.tecnocampus.library.persistence.LoanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Returning a copy: load the loan and its copy, let the domain close the loan, let dirty checking
 * write. {@link Loan#giveBack} is even idempotent, so a retry, one after the other, is harmless.
 *
 * <p>Two returns of the same loan at the same time (the return machine timed out and retried)
 * both read the loan as open and the copy as {@code ON_LOAN}, and both close it. Hibernate's
 * update writes <em>every</em> column of the stale copy object, not only the ones that changed:
 * if the copy was reserved by someone else between the two commits, the second return erases
 * that reservation (a lost update on a column this code never meant to touch).
 */
@Service
public class NaiveLoanReturnService {

    private final LoanRepository loans;
    private final Clock clock;

    public NaiveLoanReturnService(LoanRepository loans, Clock clock) {
        this.loans = loans;
        this.clock = clock;
    }

    @Transactional
    public LoanResponse giveBack(UUID loanId) {
        Loan loan = loans.findById(loanId).orElseThrow(() -> new NotFoundException("Loan", loanId));
        loan.giveBack(clock.instant());                  // the copy is loaded here, without a lock
        return LoanResponse.from(loan);
    }
}
