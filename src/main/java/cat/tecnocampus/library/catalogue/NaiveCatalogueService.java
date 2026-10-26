package cat.tecnocampus.library.catalogue;

import cat.tecnocampus.library.persistence.BookRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The obvious version: read the books, then walk from each book to its author and its subjects.
 *
 * <p>Author and subjects are lazy, so each one is loaded with its own query the first time it is
 * touched: 1 query for the books, 1 per distinct author, 1 per book for its subjects (N+1). The
 * persistence context answers repeated authors without a query, which hides part of the problem
 * when many books share an author.
 */
@Service
public class NaiveCatalogueService {

    private final BookRepository books;

    public NaiveCatalogueService(BookRepository books) {
        this.books = books;
    }

    // readOnly: Hibernate keeps no snapshots and never flushes. Cheaper entities, still entities.
    @Transactional(readOnly = true)
    public List<BookSummary> list() {
        return books.findAllOrderedByTitle().stream().map(BookSummary::of).toList();
    }

    /** The same walk over the books of one author: 20 books, but the author is loaded only once. */
    @Transactional(readOnly = true)
    public List<BookSummary> listByAuthor(String authorName) {
        return books.findByAuthorNameOrderedByTitle(authorName).stream().map(BookSummary::of).toList();
    }
}
