package cat.tecnocampus.library.reservation;

import cat.tecnocampus.library.TestcontainersConfiguration;
import cat.tecnocampus.library.domain.CopiesUnavailableException;
import cat.tecnocampus.library.domain.CopyStatus;
import cat.tecnocampus.library.domain.MemberLimitExceededException;
import cat.tecnocampus.library.persistence.CopyRepository;
import cat.tecnocampus.library.persistence.ReservationRepository;
import cat.tecnocampus.library.scenario.ConcurrentRun;
import cat.tecnocampus.library.scenario.ConcurrentRun.Attempt;
import cat.tecnocampus.library.scenario.DemoReset;
import cat.tecnocampus.library.scenario.RaceWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReservationTest {

    @Autowired NaiveReservationService naive;
    @Autowired LockingReservationService locking;
    @Autowired ReservationLookupService lookup;
    @Autowired ReservationRepository reservations;
    @Autowired CopyRepository copies;
    @Autowired RaceWindow raceWindow;
    @Autowired DemoReset reset;
    @Autowired MockMvcTester mvc;

    @BeforeEach
    void northIsBackToItsInitialState() {
        reset.resetNorthAndScenarioMembers();
    }

    @AfterEach
    void disarm() {
        raceWindow.disarm();
    }

    @Test
    void oneMemberAtATimeBothVersionsAreCorrect() {
        for (BiFunction<UUID, List<UUID>, ReservationResponse> reserve : versions()) {
            // Given
            reset.resetNorthAndScenarioMembers();
            List<UUID> copy = copies("N0001");

            // When
            ReservationResponse first = reserve.apply(member(1), copy);

            // Then
            assertThat(first.copyIds()).isEqualTo(copy);
            assertThat(status("N0001")).isEqualTo(CopyStatus.RESERVED);
            assertThatThrownBy(() -> reserve.apply(member(2), copy)).isInstanceOf(CopiesUnavailableException.class);
        }
    }

    @Test
    void aRequestWithOneTakenCopyReservesNoneOfItsCopies() {
        for (BiFunction<UUID, List<UUID>, ReservationResponse> reserve : versions()) {
            // Given
            reset.resetNorthAndScenarioMembers();
            reserve.apply(member(1), copies("N0002"));

            // When / Then
            assertThatThrownBy(() -> reserve.apply(member(2), copies("N0001", "N0002", "N0003")))
                    .isInstanceOfSatisfying(CopiesUnavailableException.class,
                            e -> assertThat(e.getCopyIds()).isEqualTo(copies("N0002")));
            assertThat(status("N0001")).isEqualTo(CopyStatus.AVAILABLE);
            assertThat(status("N0003")).isEqualTo(CopyStatus.AVAILABLE);
        }
    }

    @Test
    void naiveFiftyMembersForTheLastCopyAllSucceed() {
        // When
        List<Attempt<ReservationResponse>> attempts = fiftyForTheBestseller(naive::reserve);

        // Then: fifty promises, and the copy row can only show one of them
        assertThat(attempts).allMatch(Attempt::succeeded);
        assertThat(reservations.countByCopy(bestseller())).isEqualTo(50);
    }

    @Test
    void withLocksExactlyOneOfFiftyMembersGetsTheLastCopy() {
        // When
        List<Attempt<ReservationResponse>> attempts = fiftyForTheBestseller(locking::reserve);

        // Then
        assertThat(attempts).filteredOn(Attempt::succeeded).hasSize(1);
        assertThat(attempts).filteredOn(a -> !a.succeeded())
                .hasSize(49)
                .allSatisfy(a -> assertThat(a.error()).isInstanceOf(CopiesUnavailableException.class));
        assertThat(reservations.countByCopy(bestseller())).isEqualTo(1);
        UUID winner = attempts.stream().filter(Attempt::succeeded).findFirst().orElseThrow().value().id();
        assertThat(copies.findById(bestseller()).orElseThrow().getReservationId()).isEqualTo(winner);
    }

    @Test
    void naiveOverlappingRequestsPromiseTheSharedCopyTwice() {
        // When
        List<Attempt<ReservationResponse>> attempts = overlapping(naive::reserve);

        // Then
        assertThat(attempts).allMatch(Attempt::succeeded);
        assertThat(reservations.countByCopy(copies("N0002").getFirst())).isEqualTo(2);
    }

    @Test
    void withLocksTheLoserOfOverlappingRequestsLeavesNoTrace() {
        // When
        List<Attempt<ReservationResponse>> attempts = overlapping(locking::reserve);

        // Then: one winner; the loser's other copy is still available
        assertThat(attempts).filteredOn(Attempt::succeeded).hasSize(1);
        assertThat(reservations.countByCopy(copies("N0002").getFirst())).isEqualTo(1);
        boolean firstWon = attempts.getFirst().succeeded();
        assertThat(status(firstWon ? "N0003" : "N0001")).isEqualTo(CopyStatus.AVAILABLE);
    }

    @Test
    void requestsForTheSameCopiesInOppositeOrderNeverDeadlock() {
        for (int round = 0; round < 10; round++) {
            // Given
            reset.resetNorthAndScenarioMembers();
            List<List<UUID>> requests = List.of(copies("N0004", "N0005"), copies("N0005", "N0004"));

            // When
            List<Attempt<ReservationResponse>> attempts = race(2, i -> locking.reserve(member(51 + i), requests.get(i)));

            // Then: one success and one clean rejection, never a deadlock error
            assertThat(attempts).filteredOn(Attempt::succeeded).hasSize(1);
            assertThat(attempts).filteredOn(a -> !a.succeeded())
                    .allSatisfy(a -> assertThat(a.error()).isInstanceOf(CopiesUnavailableException.class));
        }
    }

    @Test
    void moreThanFiveCopiesAtOnceExceedTheMemberLimit() {
        assertThatThrownBy(() -> locking.reserve(member(1),
                copies("N0001", "N0002", "N0003", "N0004", "N0005", "N0006")))
                .isInstanceOf(MemberLimitExceededException.class);
    }

    @Test
    void theEndpointAnswers201WithLocationThen409WithTheUnavailableCopies() {
        String body = """
                {"memberEmail": "member01@library.test", "branch": "North", "barcodes": ["N0001"]}""";
        String other = body.replace("member01", "member02");

        assertThat(mvc.post().uri("/better/reservations").contentType(MediaType.APPLICATION_JSON).content(body))
                .hasStatus(HttpStatus.CREATED).headers().containsHeader("Location");
        assertThat(mvc.post().uri("/better/reservations").contentType(MediaType.APPLICATION_JSON).content(other))
                .hasStatus(HttpStatus.CONFLICT).hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.unavailableCopies[0]").isEqualTo(copies("N0001").getFirst().toString());
        assertThat(mvc.post().uri("/naive/reservations").contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("member01", "nobody")))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    private List<Attempt<ReservationResponse>> fiftyForTheBestseller(BiFunction<UUID, List<UUID>, ReservationResponse> reserve) {
        List<UUID> members = IntStream.rangeClosed(1, 50).mapToObj(this::member).toList();
        List<UUID> copy = List.of(bestseller());
        return race(50, i -> reserve.apply(members.get(i), copy));
    }

    private List<Attempt<ReservationResponse>> overlapping(BiFunction<UUID, List<UUID>, ReservationResponse> reserve) {
        List<List<UUID>> requests = List.of(copies("N0001", "N0002"), copies("N0003", "N0002"));
        List<UUID> members = List.of(member(51), member(52));
        return race(2, i -> reserve.apply(members.get(i), requests.get(i)));
    }

    private <T> List<Attempt<T>> race(int threads, IntFunction<T> task) {
        raceWindow.arm(threads);
        try {
            return ConcurrentRun.run(threads, task);
        } finally {
            raceWindow.disarm();
        }
    }

    private List<BiFunction<UUID, List<UUID>, ReservationResponse>> versions() {
        return List.of(naive::reserve, locking::reserve);
    }

    private UUID member(int n) {
        return lookup.memberId("member%02d@library.test".formatted(n));
    }

    private UUID bestseller() {
        return copies("N-BEST").getFirst();
    }

    private List<UUID> copies(String... barcodes) {
        return lookup.copyIds("North", List.of(barcodes));
    }

    private CopyStatus status(String barcode) {
        return copies.findById(copies(barcode).getFirst()).orElseThrow().getStatus();
    }
}
