package cat.tecnocampus.library.catalogue;

import cat.tecnocampus.library.domain.Book;
import cat.tecnocampus.library.domain.Subject;

import java.util.List;

/**
 * One book of the catalogue as the client sees it. The three versions of the catalogue return
 * exactly the same list of summaries; only the way they read the data differs.
 */
public record BookSummary(String isbn, String title, String authorName, String authorNationality,
                          List<String> subjects) {

    /** Touches the author and the subjects: with lazy associations, this is where the queries happen. */
    static BookSummary of(Book book) {
        List<String> subjects = book.getSubjects().stream().map(Subject::getName).sorted().toList();
        return new BookSummary(book.getIsbn(), book.getTitle(), book.getAuthor().getName(),
                book.getAuthor().getNationality(), subjects);
    }
}
