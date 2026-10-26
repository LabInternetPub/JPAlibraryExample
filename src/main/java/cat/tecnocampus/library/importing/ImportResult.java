package cat.tecnocampus.library.importing;

/** What an import did. A second import of the same file creates nothing and leaves everything unchanged. */
public record ImportResult(String branch, int rows, int created, int updated, int unchanged) {

    @Override
    public String toString() {
        return rows + " rows: " + created + " created, " + updated + " updated, " + unchanged + " unchanged";
    }
}
