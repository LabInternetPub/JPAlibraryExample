package cat.tecnocampus.library.persistence;

import cat.tecnocampus.library.domain.Reservation;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    /**
     * The member's active items: copies in live reservations plus open loans, in one statement.
     *
     * <p>The rule is about a set of rows. Two transactions that run this query at the same time
     * read the same total, and neither sees the rows the other is about to insert.
     */
    @Query("""
            select coalesce((select sum(r.quantity) from Reservation r
                             where r.member = m
                               and r.status = cat.tecnocampus.library.domain.ReservationStatus.ACTIVE
                               and r.expiresAt > :now), 0)
                 + (select count(l) from Loan l where l.member = m and l.returned = false)
            from Member m where m.id = :member
            """)
    long countActiveItems(@Param("member") UUID memberId, @Param("now") Instant now);

    /**
     * Expiry sweep: the ids (scalars, not entities) of a bounded page of ACTIVE reservations whose
     * deadline has passed, oldest first, served by {@code ix_reservation_expiry}. The rest are
     * left for the next run.
     */
    @Query("""
            select r.id from Reservation r
            where r.status = cat.tecnocampus.library.domain.ReservationStatus.ACTIVE and r.expiresAt <= :now
            order by r.expiresAt, r.id
            """)
    List<UUID> findExpiredIds(@Param("now") Instant now, Pageable page);

    /**
     * SELECT ... FOR UPDATE on one reservation. Careful: if the reservation is already in the
     * persistence context, this returns that same Java object, with the fields it had when it was
     * first read. Taking the lock does not refresh them (the stale-cancellation lesson).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> lockById(@Param("id") UUID id);

    /** Before any lock: whose reservation it is, as scalars. See {@link ReservationKey}. */
    @Query("select new cat.tecnocampus.library.persistence.ReservationKey(r.id, r.member.id) from Reservation r where r.id = :id")
    Optional<ReservationKey> transitionKey(@Param("id") UUID id);

    /**
     * The sweep's lock: {@code FOR UPDATE SKIP LOCKED}. If another transaction holds the row (a
     * member collecting at the desk, another sweep), it returns nothing at once instead of waiting.
     * Native SQL because JPQL has no portable way to say "skip locked".
     */
    @Query(value = "select * from reservation where id = :id for update skip locked", nativeQuery = true)
    Optional<Reservation> lockIfFree(@Param("id") UUID id);

    /** The copies of one reservation, without loading the reservation. */
    @Query("select c from Reservation r join r.copyIds c where r.id = :id")
    List<UUID> findCopyIds(@Param("id") UUID id);

    /**
     * Option B: the database decides who wins. 1 row: this collection won; 0 rows: someone else
     * decided first, or the time rule says no. Under READ COMMITTED a second UPDATE on the same row
     * waits for the first one and then re-evaluates this WHERE on the committed row.
     *
     * <p>The time rule is now written twice, here and in {@code Reservation.isLiveAt}: same
     * boundary ({@code >} here, {@code isBefore} there), pinned by the boundary tests.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Reservation r set r.status = cat.tecnocampus.library.domain.ReservationStatus.COLLECTED
            where r.id = :id and r.member.id = :member
              and r.status = cat.tecnocampus.library.domain.ReservationStatus.ACTIVE and r.expiresAt > :now
            """)
    int collectIfLive(@Param("id") UUID id, @Param("member") UUID memberId, @Param("now") Instant now);

    /** Option B, expiry: wins only if still ACTIVE and due ({@code <=}, the other side of the same boundary). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Reservation r set r.status = cat.tecnocampus.library.domain.ReservationStatus.EXPIRED
            where r.id = :id
              and r.status = cat.tecnocampus.library.domain.ReservationStatus.ACTIVE and r.expiresAt <= :now
            """)
    int expireIfDue(@Param("id") UUID id, @Param("now") Instant now);

    /** Every reservation id: the shelf-map scenarios check that none of them appears in a response. */
    @Query("select r.id from Reservation r")
    List<UUID> findAllIds();

    /** How many reservations claim this copy. More than one live reservation for a copy is a broken promise. */
    @Query("select count(r) from Reservation r join r.copyIds c where c = :copy")
    long countByCopy(@Param("copy") UUID copyId);
}
