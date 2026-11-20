package cat.tecnocampus.library.persistence;

import cat.tecnocampus.library.domain.BookOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookOfferRepository extends JpaRepository<BookOffer, UUID> {

    /**
     * The offers of these ISBNs, as scalars, in offer-id order: every purchase takes its copies in
     * the same order, so two purchases of overlapping lists cannot wait for each other in a cycle.
     */
    @Query("""
            select new cat.tecnocampus.library.persistence.OfferRow(o.id, b.isbn, b.title, o.priceCents)
            from BookOffer o join o.book b
            where b.isbn in :isbns
            order by o.id
            """)
    List<OfferRow> findOffers(@Param("isbns") Collection<String> isbns);

    /**
     * Takes one copy if there is one left. The database decides, on the row, after waiting for any
     * other purchase that is changing it: 1 = taken, 0 = sold out. A Java "read the stock, check,
     * write" would let two purchases both take the last copy.
     */
    @Modifying
    @Query("update BookOffer o set o.stock = o.stock - 1 where o.id = :id and o.stock > 0")
    int takeOne(@Param("id") UUID offerId);

    /** Puts one copy back, when it is proven that the purchase took no money. */
    @Modifying
    @Query("update BookOffer o set o.stock = o.stock + 1 where o.id = :id")
    int giveBack(@Param("id") UUID offerId);

    /** For scenarios and tests. */
    @Query("select o.stock from BookOffer o where o.book.isbn = :isbn")
    Optional<Integer> stockOf(@Param("isbn") String isbn);
}
