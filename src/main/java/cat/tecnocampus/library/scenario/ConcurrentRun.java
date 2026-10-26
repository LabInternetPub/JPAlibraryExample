package cat.tecnocampus.library.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

/**
 * Starts several requests at the same instant: every task waits on a latch until all of them
 * have been submitted, then the latch opens and they run together.
 */
public final class ConcurrentRun {

    private ConcurrentRun() {
    }

    /** The outcome of one attempt: either a value or the exception it threw. */
    public record Attempt<T>(T value, Throwable error) {

        public boolean succeeded() {
            return error == null;
        }
    }

    /**
     * Runs {@code threads} attempts together. Attempt {@code i} calls {@code task.apply(i)}.
     * Exceptions are captured, not rethrown: a rejection is an expected outcome.
     */
    public static <T> List<Attempt<T>> run(int threads, IntFunction<T> task) {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int attempt = i;
                Callable<T> call = () -> {
                    start.await();
                    return task.apply(attempt);
                };
                futures.add(executor.submit(call));
            }
            start.countDown();
            List<Attempt<T>> attempts = new ArrayList<>();
            for (Future<T> future : futures) {
                attempts.add(outcome(future));
            }
            return attempts;
        }
    }

    private static <T> Attempt<T> outcome(Future<T> future) {
        try {
            return new Attempt<>(future.get(), null);
        } catch (ExecutionException e) {
            return new Attempt<>(null, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Attempt<>(null, e);
        }
    }
}
