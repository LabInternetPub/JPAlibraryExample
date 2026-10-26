package cat.tecnocampus.library.catalogue;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The same catalogue, three ways. The responses are identical; compare the SQL log of each call. */
@RestController
public class CatalogueController {

    private final NaiveCatalogueService naive;
    private final JoinFetchCatalogueService joinFetch;
    private final ProjectionCatalogueService projection;

    public CatalogueController(NaiveCatalogueService naive, JoinFetchCatalogueService joinFetch,
                               ProjectionCatalogueService projection) {
        this.naive = naive;
        this.joinFetch = joinFetch;
        this.projection = projection;
    }

    @GetMapping("/naive/books")
    public List<BookSummary> naive(@RequestParam(required = false) String author) {
        return author == null ? naive.list() : naive.listByAuthor(author);
    }

    @GetMapping("/better/books/join-fetch")
    public List<BookSummary> joinFetch() {
        return joinFetch.list();
    }

    @GetMapping("/better/books/projection")
    public List<BookSummary> projection() {
        return projection.list();
    }
}
