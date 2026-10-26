package cat.tecnocampus.library.shelfmap;

import cat.tecnocampus.library.domain.Branch;
import cat.tecnocampus.library.domain.NotFoundException;
import cat.tecnocampus.library.persistence.BranchRepository;
import cat.tecnocampus.library.persistence.CopyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Better: the query returns only the values the map needs, already ordered, with expiry decided
 * in the query using the request's clock. No copy or book entity is built, and the response
 * writes floor and shelf once per shelf and one letter per status.
 */
@Service
public class ProjectedShelfMapService {

    private final BranchRepository branches;
    private final CopyRepository copies;
    private final Clock clock;

    public ProjectedShelfMapService(BranchRepository branches, CopyRepository copies, Clock clock) {
        this.branches = branches;
        this.copies = copies;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ShelfMapResponse get(String branchName) {
        Branch branch = branches.findByName(branchName).orElseThrow(() -> new NotFoundException("Branch", branchName));
        return ShelfMapResponse.from(branch.getName(), copies.projectShelfMap(branch.getId(), clock.instant()));
    }
}
