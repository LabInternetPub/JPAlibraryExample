package cat.tecnocampus.library.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Starts several requests at the same instant: every task waits on a latch until all of them
 * have been submitted, then the latch opens and they run together.
 */
public final class ConcurrentRun {

    private ConcurrentRun() {
    }

    /** The outcome of one attempt: either a value or the exception it threw, and how long it took. */
    public record Attempt<T>(T value, Throwable error, long nanos) {

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
            List<Future<Attempt<T>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int attempt = i;
                Callable<Attempt<T>> call = () -> {
                    start.await();
                    return attempt(() -> task.apply(attempt));
                };
                futures.add(executor.submit(call));
            }
            start.countDown();
            List<Attempt<T>> attempts = new ArrayList<>();
            for (Future<Attempt<T>> future : futures) {
                attempts.add(join(future));
            }
            return attempts;
        }
    }

    private static <T> Attempt<T> attempt(Supplier<T> task) {
        long begin = System.nanoTime();
        try {
            return new Attempt<>(task.get(), null, System.nanoTime() - begin);
        } catch (RuntimeException e) {
            return new Attempt<>(null, e, System.nanoTime() - begin);
        }
    }

    private static <T> Attempt<T> join(Future<Attempt<T>> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            return new Attempt<>(null, e.getCause(), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Attempt<>(null, e, 0);
        }
    }

    /** Median and 95th percentile of the attempts' durations, in milliseconds. */
    public static String latency(List<? extends Attempt<?>> attempts) {
        double[] ms = attempts.stream().mapToDouble(a -> a.nanos() / 1_000_000.0).sorted().toArray();
        double median = ms[ms.length / 2];
        double p95 = ms[Math.min(ms.length - 1, (int) Math.ceil(ms.length * 0.95) - 1)];
        return String.format(Locale.ROOT, "median %.1f ms, p95 %.1f ms (%d attempts)", median, p95, ms.length);
    }
}
