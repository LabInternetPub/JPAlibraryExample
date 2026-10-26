package cat.tecnocampus.library.scenario;

import org.hibernate.SessionEventListener;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts what Hibernate sends to the database: single statements and JDBC batches.
 *
 * <p>Hibernate's Statistics cannot tell a batch of 500 INSERTs from 500 single INSERTs; this
 * listener can. Hibernate creates one instance per session (registered with
 * {@code hibernate.session.events.auto}), so the counters are static and shared by all sessions.
 * They are global on purpose: run one scenario at a time.
 */
public class JdbcExecutionCounter implements SessionEventListener {

    private static final AtomicLong STATEMENTS = new AtomicLong();
    private static final AtomicLong BATCHES = new AtomicLong();

    @Override
    public void jdbcExecuteStatementStart() {
        STATEMENTS.incrementAndGet();
    }

    @Override
    public void jdbcExecuteBatchStart() {
        BATCHES.incrementAndGet();
    }

    static void reset() {
        STATEMENTS.set(0);
        BATCHES.set(0);
    }

    static long statements() {
        return STATEMENTS.get();
    }

    static long batches() {
        return BATCHES.get();
    }
}
