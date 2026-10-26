package cat.tecnocampus.library.importing;

/**
 * The file cannot be imported. Runtime on purpose: thrown inside the import transaction, it
 * rolls back whatever had already been written, so a bad file imports nothing.
 */
public class InvalidImportFileException extends RuntimeException {

    private final int line;

    public InvalidImportFileException(int line, String reason) {
        super("Line " + line + ": " + reason);
        this.line = line;
    }

    public int getLine() {
        return line;
    }
}
