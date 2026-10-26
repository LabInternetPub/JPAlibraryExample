package cat.tecnocampus.library.reservation;

import cat.tecnocampus.library.domain.CopiesUnavailableException;
import cat.tecnocampus.library.domain.MemberLimit;
import cat.tecnocampus.library.domain.MemberLimitExceededException;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * The member-limit scenarios. member53 starts with two open loans; then two browser tabs of the
 * same member reserve two different copies each, at the same instant. The limit is five items.
 * A new member's first request is also tried, to show it is accepted.
 */
@Configuration
class MemberLimitScenarios {

    private static final String LESSON = "member-limit";
    private static final String MEMBER = "member53@library.test";
    private static final String NEW_MEMBER = "member54@library.test";

    private final ReservationLookupService lookup;
    private final ReservationRepository reservations;
    private final RaceWindow raceWindow;
    private final DemoReset reset;

    MemberLimitScenarios(ReservationLookupService lookup, ReservationRepository reservations, RaceWindow raceWindow,
                         DemoReset reset) {
        this.lookup = lookup;
        this.reservations = reservations;
        this.raceWindow = raceWindow;
        this.reset = reset;
    }

    @Bean
    Scenario memberLimitNaive(LockingReservationService copyLocking) {
        return new SimpleScenario("member-limit-naive", LESSON,
                "Two tabs of a member with 2 loans reserve 2 different copies each: count, then lock the copies",
                scenario -> twoTabs(scenario, copyLocking::reserve)
                        .note("Both tabs count 2 items, both see 2 + 2 <= 5, both reserve: the member ends with 6.")
                        .note("Write skew: both read the same set of rows and write different rows. Locking the copies "
                                + "does not help: the copies are different, so neither tab ever waits for the other.")
                        .build());
    }

    @Bean
    Scenario memberLimitLocked(MemberLockingReservationService memberLocking) {
        return new SimpleScenario("member-limit-locked", LESSON,
                "Two tabs of a member with 2 loans reserve 2 different copies each: lock the member, then count",
                scenario -> twoTabs(scenario, memberLocking::reserve)
                        .note("The first tab locks the member row; the second waits for it on SELECT ... FOR UPDATE.")
                        .note("When the first commits, the second counts 4 items: 4 + 2 > 5, rejected and rolled back.")
                        .note("The member row always exists, so a member's first request has a row to lock too.")
                        .build());
    }

    private ScenarioReport.Builder twoTabs(Scenario scenario, BiFunction<UUID, List<UUID>, ReservationResponse> reserve) {
        reset.resetNorthAndScenarioMembers();
        reset.lend(MEMBER, "North", List.of("N0020", "N0021"));
        UUID member = lookup.memberId(MEMBER);
        long before = reservations.countActiveItems(member, Instant.now());
        List<List<UUID>> tabs = List.of(lookup.copyIds("North", List.of("N0011", "N0012")),
                lookup.copyIds("North", List.of("N0013", "N0014")));

        raceWindow.arm(2);
        List<Attempt<ReservationResponse>> attempts;
        try {
            attempts = ConcurrentRun.run(2, i -> reserve.apply(member, tabs.get(i)));
        } finally {
            raceWindow.disarm();
        }

        Attempt<ReservationResponse> first = ConcurrentRun.run(1, i ->
                reserve.apply(lookup.memberId(NEW_MEMBER), lookup.copyIds("North", List.of("N0015", "N0016", "N0017"))))
                .getFirst();

        return ScenarioReport.of(scenario)
                .result("limit", MemberLimit.MAXIMUM)
                .result("active items before (open loans)", before)
                .result("tabs", "2 copies each, different copies")
                .result("outcomes", outcomes(attempts))
                .result("active items afterwards", reservations.countActiveItems(member, Instant.now()))
                .result("a new member's first request (3 copies)", outcome(first));
    }

    private static Map<String, Long> outcomes(List<Attempt<ReservationResponse>> attempts) {
        return new TreeMap<>(attempts.stream().collect(Collectors.groupingBy(MemberLimitScenarios::outcome,
                Collectors.counting())));
    }

    private static String outcome(Attempt<?> attempt) {
        if (attempt.succeeded()) {
            return "201 reserved";
        }
        return switch (attempt.error()) {
            case MemberLimitExceededException e -> "409 member limit exceeded";
            case CopiesUnavailableException e -> "409 copies unavailable";
            default -> "error " + attempt.error().getClass().getSimpleName();
        };
    }
}
