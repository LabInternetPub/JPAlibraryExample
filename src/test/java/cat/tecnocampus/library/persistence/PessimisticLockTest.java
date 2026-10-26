package cat.tecnocampus.library.persistence;

import cat.tecnocampus.library.TestcontainersConfiguration;
import cat.tecnocampus.library.domain.Book;
import cat.tecnocampus.library.domain.Branch;
import cat.tecnocampus.library.domain.Condition;
import cat.tecnocampus.library.domain.Copy;
import cat.tecnocampus.library.domain.CopyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the lock does between two real transactions. Not @Transactional: each transaction is
 * committed or rolled back explicitly, as in the running application.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PessimisticLockTest {

    @Autowired TransactionTemplate transactions;
    @Autowired CopyRepository copies;
    @Autowired BookRepository books;
    @Autowired BranchRepository branches;

    private UUID copyId;

    @BeforeEach
    void aFreshCopyOfItsOwn() {
        copyId = transactions.execute(status -> {
            Branch north = branches.findByName("North").orElseThrow();
            Book book = books.findByIsbn("978-84-000001").orElseThrow();
            Copy copy = new Copy(north, book, "LOCK-" + UUID.randomUUID(), 9, "9-TEST", Condition.NEW);
            copies.save(copy);
            return copy.getId();
        });
    }

    @Test
    void aWaitingTransactionSeesTheCommittedReservation() throws Exception {
        // Given: transaction A locks the copy and holds the lock until told to finish
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CompletableFuture<Void> a = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            Copy copy = copies.lockCopies(List.of(copyId)).getFirst();
            copy.reserve(UUID.randomUUID(), Instant.now().plus(Duration.ofHours(48)), Instant.now());
            locked.countDown();
            await(finish);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // When: transaction B asks for the same lock, and A commits a moment later
        CompletableFuture<CopyStatus> b = CompletableFuture.supplyAsync(() -> transactions.execute(
                status -> copies.lockCopies(List.of(copyId)).getFirst().getStatus()));
        Thread.sleep(500);
        assertThat(b).isNotDone();
        finish.countDown();
        a.get(10, TimeUnit.SECONDS);

        // Then: B waited, and read the committed state, not the state before A
        assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo(CopyStatus.RESERVED);
    }

    @Test
    void aTransactionDoesNotWaitLongerThanTheLockTimeout() throws Exception {
        // Given: transaction A holds the lock for longer than the 5-second timeout
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CompletableFuture<Void> a = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            copies.lockCopies(List.of(copyId));
            locked.countDown();
            await(finish);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // When
        long start = System.nanoTime();
        try {
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> copies.lockCopies(List.of(copyId))))
                    .isInstanceOf(PessimisticLockingFailureException.class);
        } finally {
            finish.countDown();
            a.get(10, TimeUnit.SECONDS);
        }

        // Then
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(9));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The test never released the lock");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
