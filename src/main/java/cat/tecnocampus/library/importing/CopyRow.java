package cat.tecnocampus.library.importing;

import cat.tecnocampus.library.domain.Condition;

/** One valid line of the CSV file. {@code line} is the line number in the file, for error messages. */
public record CopyRow(int line, String isbn, String barcode, int floor, String shelf, Condition condition) {
}
