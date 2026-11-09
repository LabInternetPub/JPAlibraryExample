package cat.tecnocampus.library.loan;

import cat.tecnocampus.library.domain.NotFoundException;
import cat.tecnocampus.library.persistence.CopyRepository;
import cat.tecnocampus.library.persistence.LoanRepository;
import cat.tecnocampus.library.scenario.TransitionPauses;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Returning a copy, better: the loan row decides, with a conditional update.
 * <ul>
 *   <li>{@code closeIfOpen} returns 1 for the call that returns the loan and 0 for any other (a
 *       retry, or a second scan at the same time): the second one waits for the first one's row
 *       lock and then finds {@code returned = true}.</li>
 *   <li>Only the winner touches the copy, and only its status column, and only if it is still on
 *       loan. A copy reserved since then is not overwritten.</li>
 *   <li>A retry is answered 200 with the same loan: idempotent, not an error.</li>
 * </ul>
 */
@Service
public class ConditionalLoanReturnService {

    private final LoanRepository loans;
    private final CopyRepository copies;
    private final TransitionPauses pauses;
    private final Clock clock;

    public ConditionalLoanReturnService(LoanRepository loans, CopyRepository copies, TransitionPauses pauses,
                                        Clock clock) {
        this.loans = loans;
        this.copies = copies;
        this.pauses = pauses;
        this.clock = clock;
    }

    @Transactional
    public LoanResponse giveBack(UUID loanId) {
        UUID copyId = loans.findCopyId(loanId).orElseThrow(() -> new NotFoundException("Loan", loanId));
        pauses.observe(TransitionPauses.RETURN_READ, loanId);
        if (loans.closeIfOpen(loanId, clock.instant()) == 1
                && copies.shelveIfOnLoan(copyId) != 1) {
            throw new IllegalStateException("The copy of an open loan was not on loan: " + copyId);
        }
        return LoanResponse.from(loans.findById(loanId).orElseThrow());   // read after the update: current
    }
}
