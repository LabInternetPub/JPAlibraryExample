package cat.tecnocampus.library.expiry;

import cat.tecnocampus.library.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mandatory test of the expiry lesson: the collection / expiry interleaving, forced in both
 * orderings, ten fresh fixtures each, with a controlled clock. After both transactions have
 * committed, every fact of the invariant must match exactly one winner.
 *
 * <p>Not {@code @Transactional}: each operation commits in its own transaction on its own thread.
 * A transactional test would see its own uncommitted writes and roll them back at the end.
 *
 * <p>It failed on the naive transitions, 10 of 10 rounds in each ordering (stage 3, output in
 * design/evidence/expiry-naive-race.log). It runs here on both better options.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExpiryRaceTest {

    @Autowired ExpiryRaces races;
    @Autowired LockingExpiryTransitions locking;
    @Autowired ConditionalExpiryTransitions conditional;

    @Test
    void lockingWhenCollectionCommitsFirstItWinsAndExpiryChangesNothing() {
        List<String> rounds = ExpiryScenarios.rounds(ExpiryScenarios.ROUNDS,
                () -> races.collectionCommitsFirst(locking::collect, locking).toString());

        assertThat(rounds).containsOnly(ExpiryScenarios.REQUIRED_COLLECTION_FIRST);
    }

    @Test
    void lockingWhenExpiryCommitsFirstItWinsAndCollectionIsRefused() {
        List<String> rounds = ExpiryScenarios.rounds(ExpiryScenarios.ROUNDS,
                () -> races.expiryCommitsFirst(locking::collect, locking).toString());

        assertThat(rounds).containsOnly(ExpiryScenarios.REQUIRED_EXPIRY_FIRST);
    }

    @Test
    void conditionalWhenCollectionCommitsFirstItWinsAndExpiryChangesNothing() {
        List<String> rounds = ExpiryScenarios.rounds(ExpiryScenarios.ROUNDS,
                () -> races.collectionCommitsFirst(conditional::collect, conditional).toString());

        assertThat(rounds).containsOnly(ExpiryScenarios.REQUIRED_COLLECTION_FIRST);
    }

    @Test
    void conditionalWhenExpiryCommitsFirstItWinsAndCollectionIsRefused() {
        List<String> rounds = ExpiryScenarios.rounds(ExpiryScenarios.ROUNDS,
                () -> races.expiryCommitsFirst(conditional::collect, conditional).toString());

        assertThat(rounds).containsOnly(ExpiryScenarios.REQUIRED_EXPIRY_FIRST);
    }
}
