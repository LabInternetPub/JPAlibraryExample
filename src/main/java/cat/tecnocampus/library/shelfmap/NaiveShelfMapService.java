package cat.tecnocampus.library.shelfmap;

import cat.tecnocampus.library.domain.Branch;
import cat.tecnocampus.library.domain.NotFoundException;
import cat.tecnocampus.library.persistence.BranchRepository;
import cat.tecnocampus.library.persistence.CopyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * The obvious version: load the copies of the branch as entities (with their books, no N+1) and
 * map each one to a DTO. Correct and private, and only two statements. The cost is not in the
 * SQL: 20,000 copies and 300 books are built and kept by the persistence context, then 20,000
 * DTOs, then a JSON with 20,000 objects that repeat the same field names.
 */
@Service
public class NaiveShelfMapService {

    private final BranchRepository branches;
    private final CopyRepository copies;
    private final Clock clock;

    public NaiveShelfMapService(BranchRepository branches, CopyRepository copies, Clock clock) {
        this.branches = branches;
        this.copies = copies;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NaiveShelfMap get(String branchName) {
        Branch branch = branches.findByName(branchName).orElseThrow(() -> new NotFoundException("Branch", branchName));
        Instant now = clock.instant();
        // visibleStatusAt: an expired reservation is shown as available, decided now, in the domain.
        List<CopyItem> items = copies.findByBranchWithBooks(branch.getId()).stream()
                .map(copy -> CopyItem.of(copy, now))
                .toList();
        return new NaiveShelfMap(branch.getName(), items);
    }
}
