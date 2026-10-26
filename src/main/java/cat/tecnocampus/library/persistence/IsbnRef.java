package cat.tecnocampus.library.persistence;

import java.util.UUID;

/** Scalar projection: a book's ISBN and id, without a managed Book entity. */
public record IsbnRef(String isbn, UUID id) {
}
