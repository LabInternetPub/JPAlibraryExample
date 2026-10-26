package cat.tecnocampus.library.shelfmap;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The shelf map of a branch, naive and compact. The branch is named, not identified by id: ids
 * are random UUIDs that change at every start, and the demo calls these from scenarios.http.
 */
@RestController
public class ShelfMapController {

    private final NaiveShelfMapService naive;
    private final ProjectedShelfMapService projected;

    public ShelfMapController(NaiveShelfMapService naive, ProjectedShelfMapService projected) {
        this.naive = naive;
        this.projected = projected;
    }

    @GetMapping("/naive/branches/{branch}/shelf-map")
    public NaiveShelfMap naive(@PathVariable String branch) {
        return naive.get(branch);
    }

    @GetMapping("/better/branches/{branch}/shelf-map")
    public ShelfMapResponse projected(@PathVariable String branch) {
        return projected.get(branch);
    }
}
