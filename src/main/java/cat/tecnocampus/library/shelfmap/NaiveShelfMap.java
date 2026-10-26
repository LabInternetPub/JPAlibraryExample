package cat.tecnocampus.library.shelfmap;

import java.util.List;

/** The naive response: one JSON object per copy, every field name repeated 20,000 times. */
public record NaiveShelfMap(String branch, List<CopyItem> copies) {
}
