package cat.tecnocampus.library.shelfmap;

import cat.tecnocampus.library.domain.Copy;
import cat.tecnocampus.library.persistence.BranchRepository;
import cat.tecnocampus.library.persistence.CopyRepository;
import cat.tecnocampus.library.scenario.Measured;
import cat.tecnocampus.library.scenario.Measurement;
import cat.tecnocampus.library.scenario.Scenario;
import cat.tecnocampus.library.scenario.ScenarioReport;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * The persistence context (first-level cache): within one transaction, looking up the same id
 * twice sends one statement and returns the same Java object. In two transactions, two statements
 * and two different objects. The transactions are written out with a TransactionTemplate so the
 * boundary is visible in the code.
 */
@Component
class FirstLevelCacheScenario implements Scenario {

    private final TransactionTemplate transactions;
    private final CopyRepository copies;
    private final BranchRepository branches;
    private final Measurement measurement;

    FirstLevelCacheScenario(TransactionTemplate transactions, CopyRepository copies, BranchRepository branches,
                            Measurement measurement) {
        this.transactions = transactions;
        this.copies = copies;
        this.branches = branches;
        this.measurement = measurement;
    }

    @Override
    public String name() {
        return "first-level-cache";
    }

    @Override
    public String lesson() {
        return "shelf-map";
    }

    @Override
    public String description() {
        return "The same copy looked up twice: in one transaction, then in two transactions";
    }

    @Override
    public ScenarioReport run() {
        UUID copyId = transactions.execute(status -> {
            UUID central = branches.findByName("Central").orElseThrow().getId();
            return copies.findByBranchIdAndBarcode(central, "C000002").orElseThrow().getId();
        });

        Measured<Boolean> oneTransaction = measurement.sql(() -> transactions.execute(status -> {
            Copy first = copies.findById(copyId).orElseThrow();
            Copy second = copies.findById(copyId).orElseThrow();
            return first == second;
        }));

        Measured<Boolean> twoTransactions = measurement.sql(() -> {
            Copy first = transactions.execute(status -> copies.findById(copyId).orElseThrow());
            Copy second = transactions.execute(status -> copies.findById(copyId).orElseThrow());
            return first == second;
        });

        return ScenarioReport.of(this)
                .result("one transaction: JDBC statements", oneTransaction.sql().jdbcStatements())
                .result("one transaction: same Java object", oneTransaction.value())
                .result("two transactions: JDBC statements", twoTransactions.sql().jdbcStatements())
                .result("two transactions: same Java object", twoTransactions.value())
                .note("The second lookup in the same transaction is answered by the persistence context: no SQL.")
                .note("The persistence context ends with the transaction; a new transaction reads the row again.")
                .note("That is why every measured sample must run in its own transaction.")
                .build();
    }
}
