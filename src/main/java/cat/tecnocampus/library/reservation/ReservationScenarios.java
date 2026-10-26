package cat.tecnocampus.library.reservation;

import cat.tecnocampus.library.domain.CopiesUnavailableException;
import cat.tecnocampus.library.domain.Copy;
import cat.tecnocampus.library.persistence.CopyRepository;
import cat.tecnocampus.library.persistence.ReservationRepository;
import cat.tecnocampus.library.scenario.ConcurrentRun;
import cat.tecnocampus.library.scenario.ConcurrentRun.Attempt;
import cat.tecnocampus.library.scenario.DemoReset;
import cat.tecnocampus.library.scenario.RaceWindow;
import cat.tecnocampus.library.scenario.Scenario;
import cat.tecnocampus.library.scenario.ScenarioReport;
import cat.tecnocampus.library.scenario.SimpleScenario;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Reservation scenarios, naive and with locks, on North (reset before each run):
 * <ul>
 *   <li>bestseller: fifty members ask for the single copy at the same instant;</li>
 *   <li>overlap: two members ask for [N0001, N0002] and [N0003, N0002] at the same instant.</li>
 * </ul>
 * The race window makes every request check before any of them writes, on every run.
 */
@Configuration
class ReservationScenarios {

    private static final String LESSON = "reservation";
    private static final int MEMBERS = 50;

    private final ReservationLookupService lookup;
    private final ReservationRepository reservations;
    private final CopyRepository copies;
    private final RaceWindow raceWindow;
    private final DemoReset reset;

    ReservationScenarios(ReservationLookupService lookup, ReservationRepository reservations, CopyRepository copies,
                         RaceWindow raceWindow, DemoReset reset) {
        this.lookup = lookup;
        this.reservations = reservations;
        this.copies = copies;
        this.raceWindow = raceWindow;
        this.reset = reset;
    }

    @Bean
    Scenario reservationNaive(NaiveReservationService naive) {
        return new SimpleScenario("reservation-naive", LESSON,
                "Fifty members reserve the last copy at the same instant: check-then-act, no lock",
                scenario -> bestseller(scenario, naive::reserve)
                        .note("Every request read the copy as available before any of them wrote: all of them succeed.")
                        .note("The copy row holds one reservation id: the others were overwritten (lost update), "
                                + "but their members were told 201.")
                        .build());
    }

    @Bean
    Scenario reservationPessimistic(LockingReservationService locking) {
        return new SimpleScenario("reservation-pessimistic", LESSON,
                "Fifty members reserve the last copy at the same instant: SELECT ... FOR UPDATE",
                scenario -> bestseller(scenario, locking::reserve)
                        .note("The first request locks the copy; the other 49 wait on the SELECT ... FOR UPDATE.")
                        .note("When it commits, each of them reads the copy as RESERVED and is rejected with a rollback.")
                        .build());
    }

    @Bean
    Scenario reservationOverlapNaive(NaiveReservationService naive) {
        return new SimpleScenario("reservation-overlap-naive", LESSON,
                "Two members reserve [N0001, N0002] and [N0003, N0002] at the same instant, no lock",
                scenario -> overlap(scenario, naive::reserve)
                        .note("Both requests succeed: N0002 is promised to both, and the copy row keeps only the last one.")
                        .build());
    }

    @Bean
    Scenario reservationOverlapPessimistic(LockingReservationService locking) {
        return new SimpleScenario("reservation-overlap-pessimistic", LESSON,
                "Two members reserve [N0001, N0002] and [N0003, N0002] at the same instant, with locks",
                scenario -> overlap(scenario, locking::reserve)
                        .note("One request wins. The other finds N0002 taken, throws, and its transaction rolls back: "
                                + "its other copy stays available (a rejected request leaves no trace).")
                        .note("Both lock in id order, so they cannot wait for each other in a cycle (no deadlock).")
                        .build());
    }

    private ScenarioReport.Builder bestseller(Scenario scenario, BiFunction<UUID, List<UUID>, ReservationResponse> reserve) {
        reset.resetNorthAndScenarioMembers();
        UUID bestseller = lookup.copyIds("North", List.of("N-BEST")).getFirst();
        List<UUID> members = IntStream.rangeClosed(1, MEMBERS)
                .mapToObj(i -> lookup.memberId("member%02d@library.test".formatted(i))).toList();

        List<Attempt<ReservationResponse>> attempts = race(MEMBERS, i -> reserve.apply(members.get(i), List.of(bestseller)));

        Copy copy = copies.findById(bestseller).orElseThrow();
        List<UUID> promised = attempts.stream().filter(Attempt::succeeded).map(a -> a.value().id()).toList();
        return ScenarioReport.of(scenario)
                .result("requests", MEMBERS)
                .result("outcomes", outcomes(attempts))
                .result("reservations that claim the copy", reservations.countByCopy(bestseller))
                .result("copy status", copy.getStatus())
                .result("copy row held by one of the successful reservations", promised.contains(copy.getReservationId()))
                .result("latency", ConcurrentRun.latency(attempts));
    }

    private ScenarioReport.Builder overlap(Scenario scenario, BiFunction<UUID, List<UUID>, ReservationResponse> reserve) {
        reset.resetNorthAndScenarioMembers();
        List<UUID> ids = lookup.copyIds("North", List.of("N0001", "N0002", "N0003"));
        Map<UUID, String> barcodes = Map.of(ids.get(0), "N0001", ids.get(1), "N0002", ids.get(2), "N0003");
        List<List<UUID>> requests = List.of(List.of(ids.get(0), ids.get(1)), List.of(ids.get(2), ids.get(1)));
        List<UUID> members = List.of(lookup.memberId("member51@library.test"), lookup.memberId("member52@library.test"));

        List<Attempt<ReservationResponse>> attempts = race(2, i -> reserve.apply(members.get(i), requests.get(i)));

        Map<String, Object> state = new TreeMap<>();
        for (UUID id : ids) {
            Copy copy = copies.findById(id).orElseThrow();
            state.put(barcodes.get(id), copy.getStatus() + ", claimed by " + reservations.countByCopy(id) + " reservation(s)");
        }
        return ScenarioReport.of(scenario)
                .result("outcomes", outcomes(attempts))
                .result("copies afterwards", state);
    }

    private <T> List<Attempt<T>> race(int threads, IntFunction<T> task) {
        raceWindow.arm(threads);
        try {
            return ConcurrentRun.run(threads, task);
        } finally {
            raceWindow.disarm();
        }
    }

    /** Successes, clean rejections and anything else, by name. */
    private static Map<String, Long> outcomes(List<? extends Attempt<?>> attempts) {
        return new TreeMap<>(attempts.stream().collect(Collectors.groupingBy(ReservationScenarios::outcome,
                Collectors.counting())));
    }

    private static String outcome(Attempt<?> attempt) {
        if (attempt.succeeded()) {
            return "201 reserved";
        }
        if (attempt.error() instanceof CopiesUnavailableException) {
            return "409 copies unavailable";
        }
        return "error " + attempt.error().getClass().getSimpleName();
    }

}
