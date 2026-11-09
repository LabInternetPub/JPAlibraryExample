package cat.tecnocampus.library.scenario;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Named pause points inside the services, for forcing <em>one specific</em> interleaving.
 *
 * <p>A service calls {@link #observe(String, UUID)} at chosen stages ("the reservation has been
 * read", "the copies are locked"...). In normal operation nothing is armed and the call does
 * nothing. A scenario or a test arms a stage in one of three ways:
 * <ul>
 *   <li>{@link #stopAt}: the next thread that reaches that stage for that key stops there, tells
 *       the scenario "I'm here", and waits for "go on". The scenario decides who commits first.
 *       {@link RaceWindow} cannot do this: it only makes N threads meet.</li>
 *   <li>{@link #meetAt}: every thread that reaches the stage, for any key, waits until
 *       {@code parties} threads are there, then they all continue (cyclic, like {@link RaceWindow},
 *       but limited to one stage, so it does not disturb the other services).</li>
 *   <li>{@link #failAt}: the next thread that reaches the stage for that key throws, standing for
 *       any failure at that point (a constraint, a bug, a lost connection).</li>
 * </ul>
 * It shows that an interleaving is possible; it says nothing about how often it happens.
 */
@Component
public class TransitionPauses {

    public static final String COLLECT_READ = "collect-read";
    public static final String COLLECT_VALIDATED = "collect-validated";
    public static final String EXPIRY_READ = "expiry-read";
    public static final String EXPIRY_COPIES_LOCKED = "expiry-copies-locked";
    public static final String EXPIRY_NOTIFIED = "expiry-notified";
    public static final String CANCEL_READ = "cancel-read";
    public static final String RETURN_READ = "return-read";
    public static final String RESERVE_MEMBER_LOCKED = "reserve-member-locked";
    public static final String SWEEP_CANDIDATES_READ = "sweep-candidates-read";

    private static final Logger log = LoggerFactory.getLogger(TransitionPauses.class);
    private static final long TIMEOUT_SECONDS = 15;

    private final Map<String, Stop> stops = new ConcurrentHashMap<>();
    private final Map<String, CyclicBarrier> meetings = new ConcurrentHashMap<>();
    private final Map<String, Boolean> failures = new ConcurrentHashMap<>();

    /** Called by the services. Does nothing unless a scenario armed this stage. */
    public void observe(String stage, UUID key) {
        CyclicBarrier meeting = meetings.get(stage);
        if (meeting != null) {
            meet(meeting);
        }
        if (failures.remove(id(stage, key)) != null) {
            throw new InjectedFailure(stage, key);
        }
        Stop stop = stops.remove(id(stage, key));      // one-shot: the next thread passes freely
        if (stop != null) {
            stop.pause();
        }
    }

    /** The next thread that reaches {@code stage} for {@code key} stops there until {@link Stop#resume()}. */
    public Stop stopAt(String stage, UUID key) {
        Stop stop = new Stop(stage);
        stops.put(id(stage, key), stop);
        return stop;
    }

    /** Every thread that reaches {@code stage} waits until {@code parties} threads are there. */
    public void meetAt(String stage, int parties) {
        meetings.put(stage, new CyclicBarrier(parties));
    }

    /** The next thread that reaches {@code stage} for {@code key} throws {@link InjectedFailure}. */
    public void failAt(String stage, UUID key) {
        failures.put(id(stage, key), Boolean.TRUE);
    }

    /** Back to normal operation: releases any thread still stopped. */
    public void disarm() {
        stops.values().forEach(Stop::resume);
        stops.clear();
        meetings.values().forEach(CyclicBarrier::reset);
        meetings.clear();
        failures.clear();
    }

    private static String id(String stage, UUID key) {
        return stage + "/" + key;
    }

    private static void meet(CyclicBarrier meeting) {
        try {
            meeting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException | BrokenBarrierException e) {
            log.warn("Meeting point broken; continuing without waiting for the other threads");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One armed stop: "I'm here" for the scenario, "go on" for the stopped thread. */
    public static final class Stop {

        private final String stage;
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);

        private Stop(String stage) {
            this.stage = Objects.requireNonNull(stage);
        }

        /** Waits until a thread has stopped here. Throws if none arrives: the scenario is wrong. */
        public void awaitReached() {
            try {
                if (!reached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("No thread reached " + stage);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for " + stage, e);
            }
        }

        /**
         * Waits until a thread has stopped here, or until {@code task} has finished without
         * stopping (a better version may decide before this point, e.g. skip a locked row).
         *
         * @return true if a thread is stopped here
         */
        public boolean awaitReachedOrDone(Future<?> task) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            try {
                while (System.nanoTime() < deadline) {
                    if (reached.await(5, TimeUnit.MILLISECONDS)) {
                        return true;
                    }
                    if (task.isDone()) {
                        return false;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("No thread reached " + stage + " and the task did not finish");
        }

        public void resume() {
            resume.countDown();
        }

        private void pause() {
            reached.countDown();
            try {
                if (!resume.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("Nobody resumed {}; continuing", stage);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The failure a scenario asked for. A runtime exception: the transaction rolls back. */
    public static final class InjectedFailure extends RuntimeException {

        public InjectedFailure(String stage, UUID key) {
            super("Injected failure at " + stage + " for " + key);
        }
    }
}
