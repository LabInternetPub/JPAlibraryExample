package cat.tecnocampus.library.persistence;

import cat.tecnocampus.library.domain.Copy;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CopyRepository extends JpaRepository<Copy, UUID> {

    /** Import, naive: called once per row; with flush mode AUTO it also flushes every pending insert. */
    Optional<Copy> findByBranchIdAndBarcode(UUID branchId, String barcode);

    /** Import, better: every barcode of the branch in one query, as scalars. */
    @Query("select new cat.tecnocampus.library.persistence.BarcodeRef(c.barcode, c.id) from Copy c where c.branch.id = :branch")
    List<BarcodeRef> findBarcodeIndex(@Param("branch") UUID branchId);

    /** Shelf map, naive: managed Copy entities; their books load lazily when touched. */
    @Query("select c from Copy c where c.branch.id = :branch order by c.floor, c.shelf, c.barcode")
    List<Copy> findByBranchOrdered(@Param("branch") UUID branchId);

    /**
     * Shelf map, naive: managed Copy entities with their books fetched in the same query (the
     * catalogue lesson already avoided the N+1). Few statements, many managed entities.
     */
    @Query("select c from Copy c join fetch c.book where c.branch.id = :branch order by c.floor, c.shelf, c.barcode")
    List<Copy> findByBranchWithBooks(@Param("branch") UUID branchId);

    /**
     * Shelf map, better: scalars only. An expired reservation is returned as AVAILABLE, computed
     * with the request's clock, so the map is right without any cleanup. The reservation id and
     * the deadline are used in the condition but never selected.
     *
     * <p>The CASE works on the status as text: with an enum literal in the THEN branch, Hibernate
     * writes it as a number and PostgreSQL refuses to mix it with the text column.
     */
    @Query("""
            select new cat.tecnocampus.library.persistence.ShelfRow(
                c.id, c.barcode, c.floor, c.shelf, b.isbn,
                case when c.status = cat.tecnocampus.library.domain.CopyStatus.RESERVED and c.reservedUntil <= :now
                     then 'AVAILABLE'
                     else cast(c.status as String) end)
            from Copy c join c.book b
            where c.branch.id = :branch
            order by c.floor, c.shelf, c.barcode
            """)
    List<ShelfRow> projectShelfMap(@Param("branch") UUID branchId, @Param("now") Instant now);

    /**
     * Reservation, better: SELECT ... FOR UPDATE. Other transactions that want any of these copies
     * wait until this one commits or rolls back. Locks are taken in id order, the same order for
     * every request, so two requests for the same copies in a different order cannot deadlock.
     * The timeout bounds how long a request waits behind another one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select c from Copy c where c.id in :ids order by c.id")
    List<Copy> lockCopies(@Param("ids") Collection<UUID> ids);

    /** Reservation requests name copies by branch and barcode: ids change at every start of the example. */
    @Query("select new cat.tecnocampus.library.persistence.BarcodeRef(c.barcode, c.id) from Copy c "
            + "where c.branch.name = :branch and c.barcode in :barcodes")
    List<BarcodeRef> findIdsByBranchAndBarcodes(@Param("branch") String branch,
                                                @Param("barcodes") Collection<String> barcodes);
}
