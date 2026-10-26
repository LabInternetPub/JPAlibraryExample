package cat.tecnocampus.library.domain;

/** Small in-memory objects for domain unit tests; nothing is persisted. */
final class Fixtures {

    private Fixtures() {
    }

    static Book book() {
        return new Book("978-0-00-000000-1", "The Name of the Rose", 1980, new Author("Umberto Eco", "Italian"));
    }

    static Copy copy() {
        return new Copy(new Branch("North", "Mataró"), book(), "N000001", 1, "1-A", Condition.GOOD);
    }

    static Member member() {
        return new Member("Ana", "ana@example.org");
    }
}
