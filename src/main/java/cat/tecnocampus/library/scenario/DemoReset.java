package cat.tecnocampus.library.scenario;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Puts the data a scenario uses back to a known state, so the naive and the better scenario run
 * on identical data, in any order, any number of times. JPQL bulk statements: one statement each,
 * no entities loaded.
 */
@Component
public class DemoReset {

    private final EntityManager entityManager;

    public DemoReset(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Removes every copy of the branch. Only for branches whose copies have no loans or reservations (Annex). */
    @Transactional
    public int emptyBranch(String branchName) {
        return entityManager.createQuery("delete from Copy c where c.branch.name = :branch")
                .setParameter("branch", branchName)
                .executeUpdate();
    }

    /**
     * North back to its initial state: every copy available, and no reservation or loan of the
     * scenario members (member01 ... member60). Hibernate also deletes the reservations' rows in
     * the element-collection table.
     */
    @Transactional
    public void resetNorthAndScenarioMembers() {
        // A subquery, not r.member.email: with an implicit join, Hibernate's cleanup of the
        // element-collection table references a join it does not declare, and PostgreSQL refuses it.
        String scenarioMembers = "(select m.id from Member m where m.email like 'member%@library.test')";
        entityManager.createQuery("delete from Reservation r where r.member.id in " + scenarioMembers)
                .executeUpdate();
        entityManager.createQuery("delete from Loan l where l.member.id in " + scenarioMembers)
                .executeUpdate();
        entityManager.createQuery("""
                        update Copy c set c.status = cat.tecnocampus.library.domain.CopyStatus.AVAILABLE,
                            c.reservationId = null, c.reservedUntil = null
                        where c.branch.name = 'North'
                        """)
                .executeUpdate();
    }
}
