package cat.tecnocampus.library.catalogue;

import cat.tecnocampus.library.persistence.BookRepository;
import cat.tecnocampus.library.persistence.BookRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Better, for a read-only response: the query returns only the values the response needs
 * (a constructor expression into a record), so no entity is managed at all. The price is that
 * the rows come flat, one per book and subject, and the service groups them.
 */
@Service
public class ProjectionCatalogueService {

    private final BookRepository books;

    public ProjectionCatalogueService(BookRepository books) {
        this.books = books;
    }

    @Transactional(readOnly = true)
    public List<BookSummary> list() {
        // Rows arrive ordered by title, book and subject; a LinkedHashMap keeps that order.
        Map<UUID, List<BookRow>> byBook = new LinkedHashMap<>();
        for (BookRow row : books.findCatalogueRows()) {
            byBook.computeIfAbsent(row.bookId(), id -> new ArrayList<>()).add(row);
        }
        return byBook.values().stream().map(ProjectionCatalogueService::toSummary).toList();
    }

    private static BookSummary toSummary(List<BookRow> rows) {
        BookRow first = rows.getFirst();
        List<String> subjects = rows.stream().map(BookRow::subject).filter(s -> s != null).toList();
        return new BookSummary(first.isbn(), first.title(), first.authorName(), first.authorNationality(), subjects);
    }
}
