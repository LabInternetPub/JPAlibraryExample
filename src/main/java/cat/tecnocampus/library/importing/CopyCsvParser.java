package cat.tecnocampus.library.importing;

import cat.tecnocampus.library.domain.Condition;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads and validates the whole file before anything is written: a bad value on the last line is
 * found before the first copy is stored. Shared by the naive and the batched import, so they
 * accept and reject exactly the same files.
 *
 * <p>Format: a header {@code isbn,barcode,floor,shelf,condition}, then one copy per line.
 */
@Component
public class CopyCsvParser {

    static final String HEADER = "isbn,barcode,floor,shelf,condition";
    static final int MAX_ROWS = 100_000;

    public List<CopyRow> parse(InputStream csv) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null || !header.strip().equals(HEADER)) {
                throw new InvalidImportFileException(1, "the header must be '" + HEADER + "'");
            }
            List<CopyRow> rows = new ArrayList<>();
            Set<String> barcodes = new HashSet<>();
            int lineNumber = 1;
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                CopyRow row = parseLine(lineNumber, line);
                if (!barcodes.add(row.barcode())) {
                    throw new InvalidImportFileException(lineNumber, "barcode " + row.barcode() + " appears twice");
                }
                rows.add(row);
                if (rows.size() > MAX_ROWS) {
                    throw new InvalidImportFileException(lineNumber, "more than " + MAX_ROWS + " copies");
                }
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CopyRow parseLine(int line, String text) {
        String[] fields = text.split(",", -1);
        if (fields.length != 5) {
            throw new InvalidImportFileException(line, "expected 5 fields, found " + fields.length);
        }
        String isbn = required(line, "isbn", fields[0]);
        String barcode = required(line, "barcode", fields[1]);
        int floor = floor(line, fields[2]);
        String shelf = required(line, "shelf", fields[3]);
        Condition condition = condition(line, fields[4]);
        return new CopyRow(line, isbn, barcode, floor, shelf, condition);
    }

    private static String required(int line, String field, String value) {
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            throw new InvalidImportFileException(line, field + " is empty");
        }
        return stripped;
    }

    private static int floor(int line, String value) {
        try {
            int floor = Integer.parseInt(value.strip());
            if (floor < 0 || floor > 99) {
                throw new InvalidImportFileException(line, "floor must be between 0 and 99");
            }
            return floor;
        } catch (NumberFormatException e) {
            throw new InvalidImportFileException(line, "floor is not a number: " + value);
        }
    }

    private static Condition condition(int line, String value) {
        try {
            return Condition.valueOf(value.strip());
        } catch (IllegalArgumentException e) {
            throw new InvalidImportFileException(line, "unknown condition: " + value);
        }
    }
}
