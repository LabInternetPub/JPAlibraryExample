package cat.tecnocampus.library.importing;

import cat.tecnocampus.library.domain.Condition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CopyCsvParserTest {

    private final CopyCsvParser parser = new CopyCsvParser();

    @Test
    void aValidFileGivesOneRowPerLine() {
        // Given
        String csv = """
                isbn,barcode,floor,shelf,condition
                978-84-000001,X1,1,1-01,GOOD
                978-84-000002,X2,2,2-07,DAMAGED
                """;

        // When
        List<CopyRow> rows = parse(csv);

        // Then
        assertThat(rows).containsExactly(
                new CopyRow(2, "978-84-000001", "X1", 1, "1-01", Condition.GOOD),
                new CopyRow(3, "978-84-000002", "X2", 2, "2-07", Condition.DAMAGED));
    }

    @Test
    void aBadValueOnTheLastLineRejectsTheWholeFile() {
        // Given
        String csv = """
                isbn,barcode,floor,shelf,condition
                978-84-000001,X1,1,1-01,GOOD
                978-84-000002,X2,2,2-07,BROKEN
                """;

        // When / Then
        assertThatThrownBy(() -> parse(csv))
                .isInstanceOfSatisfying(InvalidImportFileException.class, e -> assertThat(e.getLine()).isEqualTo(3))
                .hasMessageContaining("unknown condition");
    }

    @Test
    void theHeaderIsRequired() {
        assertThatThrownBy(() -> parse("978-84-000001,X1,1,1-01,GOOD\n"))
                .isInstanceOf(InvalidImportFileException.class).hasMessageContaining("header");
    }

    @Test
    void aBarcodeCannotAppearTwiceInTheFile() {
        // Given
        String csv = """
                isbn,barcode,floor,shelf,condition
                978-84-000001,X1,1,1-01,GOOD
                978-84-000002,X1,2,2-07,GOOD
                """;

        // When / Then
        assertThatThrownBy(() -> parse(csv)).hasMessageContaining("appears twice");
    }

    @Test
    void malformedLinesAreRejected() {
        assertThatThrownBy(() -> parse("isbn,barcode,floor,shelf,condition\n978,X1,1,1-01\n"))
                .hasMessageContaining("expected 5 fields");
        assertThatThrownBy(() -> parse("isbn,barcode,floor,shelf,condition\n978,X1,first,1-01,GOOD\n"))
                .hasMessageContaining("floor is not a number");
        assertThatThrownBy(() -> parse("isbn,barcode,floor,shelf,condition\n978,,1,1-01,GOOD\n"))
                .hasMessageContaining("barcode is empty");
    }

    private List<CopyRow> parse(String csv) {
        return parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }
}
