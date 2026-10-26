package cat.tecnocampus.library.catalogue;

import cat.tecnocampus.library.persistence.BookRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Better: one query brings the books together with their authors and subjects (JOIN FETCH), so
 * walking the associations afterwards sends no more SQL. The mapping code is the same as the
 * naive version; only the query changed.
 *
 * <p>Hibernate still builds managed entities from the rows: books, authors and subjects.
 */
@Service
public class JoinFetchCatalogueService {

    private final BookRepository books;

    public JoinFetchCatalogueService(BookRepository books) {
        this.books = books;
    }

    @Transactional(readOnly = true)
    public List<BookSummary> list() {
        return books.findAllWithAuthorAndSubjects().stream().map(BookSummary::of).toList();
    }
}
