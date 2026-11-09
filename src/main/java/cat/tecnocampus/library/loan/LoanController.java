package cat.tecnocampus.library.loan;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Returning a loaned copy. A retried return answers 200 with the same loan, not an error. */
@RestController
public class LoanController {

    private final NaiveLoanReturnService naive;

    public LoanController(NaiveLoanReturnService naive) {
        this.naive = naive;
    }

    @PostMapping("/naive/loans/{id}/return")
    public LoanResponse giveBack(@PathVariable UUID id) {
        return naive.giveBack(id);
    }
}
