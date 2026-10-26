package cat.tecnocampus.library.persistence;

import java.util.UUID;

/**
 * One row per book and subject (a book without subjects gives one row with a null subject).
 * The service groups the rows by book.
 */
public record BookRow(UUID bookId, String isbn, String title, String authorName, String authorNationality,
                      String subject) {
}
