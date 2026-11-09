package cat.tecnocampus.library.expiry;

import cat.tecnocampus.library.TestcontainersConfiguration;
import org.junit.jupiter.api.Disabled;
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
 * <p>Disabled after capturing its failure on the naive transitions (stage 3 of the lesson); the
 * output is in design/evidence/expiry-naive-race.log. It is re-enabled with the fix.
 */
@Disabled("""
        Captured failure on NaiveExpiryTransitions, 10 of 10 rounds in each ordering:
        collection first -> collect=200 expire=true, reservation=EXPIRED, copy=AVAILABLE, openLoans=1, notices=1;
        expiry first -> collect=200 expire=true, reservation=COLLECTED, copy=AVAILABLE, openLoans=1, notices=1.
        See design/evidence/expiry-naive-race.log. Re-enabled with the better transitions.""")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExpiryRaceTest {

    @Autowired ExpiryRaces races;
    @Autowired NaiveExpiryTransitions transitions;

    @Test
    void whenCollectionCommitsFirstItWinsAndExpiryChangesNothing() {
        List<String> rounds = ExpiryScenarios.rounds(ExpiryScenarios.ROUNDS,
                () -> races.collectionCommitsFirst(transitions::collect, transitions).toString());

        assertThat(rounds).containsOnly(ExpiryScenarios.REQUIRED_COLLECTION_FIRST);
    }

    @Test
    void whenExpiryCommitsFirstItWinsAndCollectionIsRefused() {
        List<String> rounds = ExpiryScenarios.rounds(ExpiryScenarios.ROUNDS,
                () -> races.expiryCommitsFirst(transitions::collect, transitions).toString());

        assertThat(rounds).containsOnly(ExpiryScenarios.REQUIRED_EXPIRY_FIRST);
    }
}
