package cat.tecnocampus.library.persistence;

import cat.tecnocampus.library.domain.Book;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookRepository extends JpaRepository<Book, UUID> {

    /** Import, naive: called once per row of the file. */
    Optional<Book> findByIsbn(String isbn);

    /** Import, better: every ISBN of the catalogue in one query, as scalars. */
    @Query("select new cat.tecnocampus.library.persistence.IsbnRef(b.isbn, b.id) from Book b")
    List<IsbnRef> findIsbnIndex();

    /** Catalogue, naive: the books only; authors and subjects load lazily when touched. */
    @Query("select b from Book b order by b.title, b.id")
    List<Book> findAllOrderedByTitle();

    /** Catalogue, naive, books of one author: shows the first-level cache answering repeated author lookups. */
    @Query("select b from Book b where b.author.name = :author order by b.title, b.id")
    List<Book> findByAuthorNameOrderedByTitle(@Param("author") String author);

    /**
     * Catalogue, JOIN FETCH: books, their author and their subjects in one statement.
     * The rows are still turned into managed entities.
     */
    @Query("""
            select distinct b from Book b
            join fetch b.author
            left join fetch b.subjects
            order by b.title, b.id
            """)
    List<Book> findAllWithAuthorAndSubjects();

    /** Catalogue, projection: scalars only, no managed entities. */
    @Query("""
            select new cat.tecnocampus.library.persistence.BookRow(
                b.id, b.isbn, b.title, a.name, a.nationality, s.name)
            from Book b
            join b.author a
            left join b.subjects s
            order by b.title, b.id, s.name
            """)
    List<BookRow> findCatalogueRows();
}
