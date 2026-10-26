package cat.tecnocampus.library.scenario;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentRunAndRaceWindowTest {

    @Test
    void everyAttemptRunsAndExceptionsAreCapturedNotThrown() {
        // When
        List<ConcurrentRun.Attempt<Integer>> attempts = ConcurrentRun.run(10, i -> {
            if (i % 2 == 0) {
                throw new IllegalStateException("rejected " + i);
            }
            return i;
        });

        // Then
        assertThat(attempts).hasSize(10);
        assertThat(attempts).filteredOn(ConcurrentRun.Attempt::succeeded).extracting(ConcurrentRun.Attempt::value)
                .containsExactly(1, 3, 5, 7, 9);
        assertThat(attempts).filteredOn(a -> !a.succeeded())
                .allSatisfy(a -> assertThat(a.error()).isInstanceOf(IllegalStateException.class));
    }

    @Test
    void anArmedRaceWindowLetsNoThreadContinueUntilAllHaveArrived() {
        // Given
        RaceWindow window = new RaceWindow();
        window.arm(8);
        AtomicInteger arrived = new AtomicInteger();

        // When: each thread records how many had arrived when it got past the window
        List<ConcurrentRun.Attempt<Integer>> attempts = ConcurrentRun.run(8, i -> {
            arrived.incrementAndGet();
            window.pause();
            return arrived.get();
        });

        // Then
        assertThat(attempts).extracting(ConcurrentRun.Attempt::value).containsOnly(8);
    }

    @Test
    void aDisarmedRaceWindowDoesNotWait() {
        // Given
        RaceWindow window = new RaceWindow();
        window.arm(2);
        window.disarm();

        // When
        long start = System.nanoTime();
        window.pause();

        // Then
        assertThat(System.nanoTime() - start).isLessThan(1_000_000_000L);
    }
}
