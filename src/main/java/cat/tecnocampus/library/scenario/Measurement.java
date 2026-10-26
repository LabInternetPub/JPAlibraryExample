package cat.tecnocampus.library.scenario;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Arrays;
import java.util.function.Supplier;

/**
 * The measuring tools every scenario shares.
 *
 * <p>Each action passed here should be a call to an application service, so it runs in its own
 * transaction: otherwise the first-level cache of a previous call answers part of the next one,
 * and the numbers look better than they are.
 */
@Component
public class Measurement {

    private final Statistics statistics;
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();

    public Measurement(EntityManagerFactory entityManagerFactory) {
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    /** Runs the action once and counts what Hibernate sent to the database. */
    public <T> Measured<T> sql(Supplier<T> action) {
        statistics.clear();
        JdbcExecutionCounter.reset();
        T value = action.get();
        SqlCounts counts = new SqlCounts(JdbcExecutionCounter.statements(), JdbcExecutionCounter.batches(),
                statistics.getEntityLoadCount(), statistics.getEntityInsertCount(),
                statistics.getEntityUpdateCount(), statistics.getCollectionFetchCount());
        return new Measured<>(value, counts);
    }

    /**
     * Warm-up calls first (class loading, JIT, connection pool), then several timed samples.
     * One run is not a measurement: compare medians.
     */
    public Timing time(int warmups, int samples, Runnable action) {
        for (int i = 0; i < warmups; i++) {
            action.run();
        }
        double[] ms = new double[samples];
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            action.run();
            ms[i] = (System.nanoTime() - start) / 1_000_000.0;
        }
        Arrays.sort(ms);
        double median = samples % 2 == 1 ? ms[samples / 2] : (ms[samples / 2 - 1] + ms[samples / 2]) / 2;
        return new Timing(samples, median, ms[0], ms[samples - 1]);
    }

    /**
     * Peak heap growth while the action runs, sampled every 2 ms by a background thread.
     * Noisy: the garbage collector runs whenever it wants. Use it to compare orders of magnitude
     * (megabytes vs hundreds of megabytes), never small differences.
     */
    public HeapUse heap(Runnable action) {
        System.gc();
        long baseline = memory.getHeapMemoryUsage().getUsed();
        HeapSampler sampler = new HeapSampler(memory);
        Thread thread = Thread.ofPlatform().daemon().name("heap-sampler").start(sampler);
        try {
            action.run();
        } finally {
            sampler.stop();
            joinQuietly(thread);
        }
        return new HeapUse(baseline, Math.max(0, sampler.peak() - baseline));
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record HeapUse(long baselineBytes, long peakIncreaseBytes) {

        public String peakIncrease() {
            return String.format("%.1f MB", peakIncreaseBytes / (1024.0 * 1024.0));
        }
    }

    private static final class HeapSampler implements Runnable {

        private final MemoryMXBean memory;
        private volatile boolean running = true;
        private volatile long peak;

        HeapSampler(MemoryMXBean memory) {
            this.memory = memory;
        }

        @Override
        public void run() {
            while (running) {
                peak = Math.max(peak, memory.getHeapMemoryUsage().getUsed());
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        void stop() {
            peak = Math.max(peak, memory.getHeapMemoryUsage().getUsed());
            running = false;
        }

        long peak() {
            return peak;
        }
    }
}
