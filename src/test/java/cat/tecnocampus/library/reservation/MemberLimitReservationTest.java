package cat.tecnocampus.library.reservation;

import cat.tecnocampus.library.TestcontainersConfiguration;
import cat.tecnocampus.library.domain.MemberLimitExceededException;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** At most five active items per member: open loans plus copies in live reservations. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MemberLimitReservationTest {

    private static final String MEMBER = "member53@library.test";

    @Autowired LockingReservationService copyLocking;
    @Autowired MemberLockingReservationService memberLocking;
    @Autowired ReservationLookupService lookup;
    @Autowired ReservationRepository reservations;
    @Autowired RaceWindow raceWindow;
    @Autowired DemoReset reset;
    @Autowired MockMvcTester mvc;

    @BeforeEach
    void aMemberWithTwoOpenLoans() {
        reset.resetNorthAndScenarioMembers();
        reset.lend(MEMBER, "North", List.of("N0020", "N0021"));
    }

    @AfterEach
    void disarm() {
        raceWindow.disarm();
    }

    @Test
    void openLoansCountTowardsTheLimitOneRequestAtATime() {
        // Given: 2 loans + 2 reserved = 4
        memberLocking.reserve(member(), copies("N0011", "N0012"));

        // When / Then: 4 + 2 > 5
        assertThatThrownBy(() -> memberLocking.reserve(member(), copies("N0013", "N0014")))
                .isInstanceOfSatisfying(MemberLimitExceededException.class, e -> {
                    assertThat(e.getExisting()).isEqualTo(4);
                    assertThat(e.getRequested()).isEqualTo(2);
                });
        assertThat(activeItems()).isEqualTo(4);
    }

    @Test
    void lockingOnlyTheCopiesLetsTwoTabsOfOneMemberExceedTheLimit() {
        // When
        List<Attempt<ReservationResponse>> tabs = twoTabs(copyLocking::reserve);

        // Then: write skew, both reserve
        assertThat(tabs).allMatch(Attempt::succeeded);
        assertThat(activeItems()).isEqualTo(6);
    }

    @Test
    void lockingTheMemberLetsOnlyOneOfTwoTabsThrough() {
        // When
        List<Attempt<ReservationResponse>> tabs = twoTabs(memberLocking::reserve);

        // Then
        assertThat(tabs).filteredOn(Attempt::succeeded).hasSize(1);
        assertThat(tabs).filteredOn(t -> !t.succeeded()).singleElement()
                .satisfies(t -> assertThat(t.error()).isInstanceOf(MemberLimitExceededException.class));
        assertThat(activeItems()).isEqualTo(4);
    }

    @Test
    void aMembersFirstRequestIsAccepted() {
        // Given: a member with no loans and no reservations
        UUID newMember = lookup.memberId("member54@library.test");

        // When
        ReservationResponse reservation = memberLocking.reserve(newMember, copies("N0015", "N0016", "N0017"));

        // Then
        assertThat(reservation.copyIds()).hasSize(3);
        assertThat(reservations.countActiveItems(newMember, Instant.now())).isEqualTo(3);
    }

    @Test
    void theEndpointAnswers409WithTheMemberLimitProblem() {
        String body = """
                {"memberEmail": "member53@library.test", "branch": "North", "barcodes": ["N0011", "N0012", "N0013", "N0014"]}""";

        assertThat(mvc.post().uri("/better/reservations/member-locked").contentType(MediaType.APPLICATION_JSON).content(body))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.type").isEqualTo("/problems/member-limit-exceeded");
    }

    private List<Attempt<ReservationResponse>> twoTabs(BiFunction<UUID, List<UUID>, ReservationResponse> reserve) {
        List<List<UUID>> tabs = List.of(copies("N0011", "N0012"), copies("N0013", "N0014"));
        UUID member = member();
        raceWindow.arm(2);
        try {
            return ConcurrentRun.run(2, i -> reserve.apply(member, tabs.get(i)));
        } finally {
            raceWindow.disarm();
        }
    }

    private UUID member() {
        return lookup.memberId(MEMBER);
    }

    private long activeItems() {
        return reservations.countActiveItems(member(), Instant.now());
    }

    private List<UUID> copies(String... barcodes) {
        return lookup.copyIds("North", List.of(barcodes));
    }
}
