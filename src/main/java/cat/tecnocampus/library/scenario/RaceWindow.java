package cat.tecnocampus.library.scenario;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A point in a service where a concurrency scenario can make all threads meet.
 *
 * <p>In normal operation {@link #pause()} does nothing. A concurrency scenario arms it with the
 * number of threads; then every thread that reaches {@code pause()} waits until all of them have
 * arrived, and they continue together. Placed between the read and the write of the naive
 * version, it makes the bad interleaving happen on every run instead of only when the timing is
 * unlucky. It shows that the schedule is possible; it does not say how often it happens in real
 * traffic.
 *
 * <p>In a version that locks, the pause goes <em>before</em> the locking read: a thread that holds
 * a lock and waits for the others here would wait for threads that are waiting for its lock.
 */
@Component
public class RaceWindow {

    private static final Logger log = LoggerFactory.getLogger(RaceWindow.class);
    private static final long TIMEOUT_SECONDS = 10;

    private volatile CyclicBarrier barrier;

    public void pause() {
        CyclicBarrier current = barrier;
        if (current == null) {
            return;
        }
        try {
            current.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException | BrokenBarrierException e) {
            // Some thread never arrived (it failed earlier): carry on rather than block forever.
            log.warn("Race window broken; continuing without waiting for the other threads");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void arm(int threads) {
        barrier = new CyclicBarrier(threads);
    }

    public void disarm() {
        barrier = null;
    }
}
