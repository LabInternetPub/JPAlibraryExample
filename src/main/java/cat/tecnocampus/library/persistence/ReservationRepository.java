package cat.tecnocampus.library.persistence;

import cat.tecnocampus.library.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
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

    /** Every reservation id: the shelf-map scenarios check that none of them appears in a response. */
    @Query("select r.id from Reservation r")
    List<UUID> findAllIds();
}
