package cat.tecnocampus.library.expiry;

import cat.tecnocampus.library.domain.Copy;
import cat.tecnocampus.library.domain.Member;
import cat.tecnocampus.library.domain.Reservation;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Many reservations at once, for the sweep scenarios: written directly, not through the
 * reservation service, because a fixture of hundreds of reservations would go over the member
 * limit. They use available copies of Central (North has only 31) and the scenario members,
 * whom {@code DemoReset} cleans up, copies included.
 */
@Component
public class ExpiryFixtures {

    private final EntityManager entityManager;

    public ExpiryFixtures(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** One fixture reservation and the copy it holds. */
    public record Reserved(UUID reservationId, UUID copyId) {
    }

    /**
     * {@code count} one-copy reservations, the i-th created at {@code firstCreatedAt + i seconds},
     * so their deadlines, and the sweep's order, follow the returned list.
     */
    @Transactional
    public List<Reserved> reservationsOnCentral(int count, Instant firstCreatedAt) {
        List<Member> members = entityManager.createQuery(
                        "select m from Member m where m.email like 'member%@library.test' order by m.email", Member.class)
                .getResultList();
        List<Copy> free = entityManager.createQuery("""
                        select c from Copy c
                        where c.branch.name = 'Central' and c.status = cat.tecnocampus.library.domain.CopyStatus.AVAILABLE
                        order by c.barcode
                        """, Copy.class)
                .setMaxResults(count)
                .getResultList();
        if (free.size() < count) {
            throw new IllegalStateException("Only " + free.size() + " available copies in Central");
        }
        List<Reserved> reserved = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Instant createdAt = firstCreatedAt.plusSeconds(i);
            Copy copy = free.get(i);
            Reservation reservation = new Reservation(members.get(i % members.size()), List.of(copy.getId()), createdAt);
            entityManager.persist(reservation);
            reservation.reserve(List.of(copy), createdAt);
            reserved.add(new Reserved(reservation.getId(), copy.getId()));
        }
        return reserved;
    }
}
