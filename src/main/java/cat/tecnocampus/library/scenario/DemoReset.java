package cat.tecnocampus.library.scenario;

import cat.tecnocampus.library.domain.Copy;
import cat.tecnocampus.library.domain.Loan;
import cat.tecnocampus.library.domain.Member;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

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

    /** Lends copies of a branch to a member, as the starting point of a scenario. */
    @Transactional
    public void lend(String memberEmail, String branch, List<String> barcodes) {
        Member member = entityManager.createQuery("select m from Member m where m.email = :email", Member.class)
                .setParameter("email", memberEmail).getSingleResult();
        List<Copy> copies = entityManager.createQuery(
                        "select c from Copy c where c.branch.name = :branch and c.barcode in :barcodes", Copy.class)
                .setParameter("branch", branch).setParameter("barcodes", barcodes).getResultList();
        Instant now = Instant.now();
        copies.forEach(copy -> entityManager.persist(new Loan(member, copy, now)));
    }
}
