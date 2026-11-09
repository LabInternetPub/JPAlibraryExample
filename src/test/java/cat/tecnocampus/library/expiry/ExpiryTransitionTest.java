package cat.tecnocampus.library.expiry;

import cat.tecnocampus.library.MovableClock;
import cat.tecnocampus.library.TestcontainersConfiguration;
import cat.tecnocampus.library.domain.Copy;
import cat.tecnocampus.library.domain.CopyStatus;
import cat.tecnocampus.library.domain.NotFoundException;
import cat.tecnocampus.library.domain.ReservationNotActiveException;
import cat.tecnocampus.library.domain.ReservationStatus;
import cat.tecnocampus.library.loan.LoanResponse;
import cat.tecnocampus.library.loan.NaiveLoanReturnService;
import cat.tecnocampus.library.persistence.CopyRepository;
import cat.tecnocampus.library.persistence.LoanRepository;
import cat.tecnocampus.library.persistence.ReservationRepository;
import cat.tecnocampus.library.reservation.MemberLockingReservationService;
import cat.tecnocampus.library.reservation.ReservationLookupService;
import cat.tecnocampus.library.reservation.ReservationResponse;
import cat.tecnocampus.library.scenario.DemoReset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Scenario S1 of the expiry lesson, on the naive transitions: one operation at a time, with the
 * clock moved to either side of the deadline. Everything here passes, and that is the point:
 * sequential tests cannot see the races of the naive version, which only exist when two
 * transactions overlap.
 *
 * <p>Not {@code @Transactional}: each call commits in its own transaction, and the facts are read
 * back afterwards through fresh repository calls.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ExpiryTransitionTest {

    private static final Instant MONDAY_10 = Instant.parse("2026-11-02T10:00:00Z");

    @Autowired NaiveExpiryTransitions naive;
    @Autowired NaiveLoanReturnService returns;
    @Autowired MemberLockingReservationService reserving;
    @Autowired ReservationLookupService lookup;
    @Autowired ReservationRepository reservations;
    @Autowired CopyRepository copies;
    @Autowired LoanRepository loans;
    @Autowired ExpiryNotices notices;
    @Autowired MovableClock clock;
    @Autowired DemoReset reset;
    @Autowired ApplicationContext context;
    @Autowired MockMvcTester mvc;

    private ExpirySweep sweep;

    @BeforeEach
    void startFromAKnownStateOnMonday() {
        reset.resetNorthAndScenarioMembers();
        notices.clear();
        clock.fixAt(MONDAY_10);
        sweep = new ExpirySweep(reservations, naive, clock);
    }

    @AfterEach
    void backToTheSystemClock() {
        clock.reset();
    }

    @Test
    void theScheduledSweepIsOffInTests() {
        assertThat(context.getBeansOfType(ExpirySweepScheduler.class)).isEmpty();
    }

    @Test
    void collectingOneNanosecondBeforeTheDeadlineTurnsTheReservationIntoLoans() {
        // Given
        ReservationResponse reservation = reserve(1, "N0001");
        clock.fixAt(reservation.expiresAt().minusNanos(1));

        // When
        CollectionResponse collected = naive.collect(member(1), reservation.id());

        // Then
        assertThat(collected.loanIds()).hasSize(1);
        assertThat(status(reservation)).isEqualTo(ReservationStatus.COLLECTED);
        assertThat(copy("N0001").getStatus()).isEqualTo(CopyStatus.ON_LOAN);
        assertThat(copy("N0001").getReservationId()).isNull();
        assertThat(loans.countOpenByCopy(copyId("N0001"))).isEqualTo(1);
        assertThat(activeItems(1)).isEqualTo(1);       // one reserved copy became one loan

        // And after the old deadline nothing expires it: collection removed the deadline
        clock.advance(java.time.Duration.ofHours(1));
        assertThat(sweep.sweepOnce().candidates()).isZero();
        assertThat(status(reservation)).isEqualTo(ReservationStatus.COLLECTED);
        assertThat(copy("N0001").visibleStatusAt(clock.instant())).isEqualTo(CopyStatus.ON_LOAN);
        assertThat(notices.total()).isZero();
        assertThat(mvc.get().uri("/reservations/{id}", reservation.id()))
                .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("COLLECTED");
    }

    @Test
    void exactlyAtTheDeadlineEveryReaderSeesItExpiredBeforeAnySweep() {
        // Given
        ReservationResponse reservation = reserve(1, "N0001", "N0002");

        // When
        clock.fixAt(reservation.expiresAt());

        // Then: nothing has been written, every reader applies the rule
        assertThat(status(reservation)).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(copy("N0001").getStatus()).isEqualTo(CopyStatus.RESERVED);
        assertThat(copy("N0001").visibleStatusAt(clock.instant())).isEqualTo(CopyStatus.AVAILABLE);
        assertThat(activeItems(1)).isZero();
        assertThat(mvc.get().uri("/reservations/{id}", reservation.id()))
                .hasStatus(HttpStatus.GONE).bodyJson().extractingPath("$.type").isEqualTo("/problems/reservation-expired");
        assertThatThrownBy(() -> naive.collect(member(1), reservation.id()))
                .isInstanceOf(ReservationNotActiveException.class);
        assertThat(mvc.post().uri("/naive/reservations/{id}/collection", reservation.id())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"memberEmail": "member01@library.test"}"""))
                .hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.type")
                .isEqualTo("/problems/reservation-not-active");
        assertThat(loans.countOpenByCopy(copyId("N0001"))).isZero();
    }

    @Test
    void theSweepExpiresOnceAndReleasesEveryCopy() {
        // Given
        ReservationResponse reservation = reserve(1, "N0001", "N0002");
        clock.fixAt(reservation.expiresAt().minusNanos(1));
        assertThat(sweep.sweepOnce().candidates()).isZero();     // not due yet
        clock.fixAt(reservation.expiresAt());

        // When
        ExpirySweep.SweepResult first = sweep.sweepOnce();
        ExpirySweep.SweepResult second = sweep.sweepOnce();

        // Then
        assertThat(first).isEqualTo(new ExpirySweep.SweepResult(1, 1, 0));
        assertThat(second).isEqualTo(new ExpirySweep.SweepResult(0, 0, 0));
        assertThat(naive.expire(reservation.id())).isFalse();   // even asked directly
        assertThat(status(reservation)).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(List.of(copy("N0001"), copy("N0002"))).allSatisfy(c -> {
            assertThat(c.getStatus()).isEqualTo(CopyStatus.AVAILABLE);
            assertThat(c.getReservationId()).isNull();
        });
        assertThat(notices.sentFor(reservation.id())).isEqualTo(1);
        assertThat(activeItems(1)).isZero();
    }

    @Test
    void anExpiredCopyCanBeReservedAgainBeforeAnySweep() {
        // Given
        ReservationResponse anna = reserve(1, "N0001");
        clock.fixAt(anna.expiresAt());

        // When: Bruno's reservation takes over the copy (lazy expiry on a write)
        ReservationResponse bruno = reserve(2, "N0001");

        // Then
        assertThat(copy("N0001").getReservationId()).isEqualTo(bruno.id());
        assertThat(status(anna)).isEqualTo(ReservationStatus.ACTIVE);   // until the sweep stores it
    }

    @Test
    void theMemberCancelsBeforeTheDeadlineButNotAtIt() {
        // Given
        ReservationResponse cancelled = reserve(1, "N0001");
        ReservationResponse late = reserve(1, "N0002");
        clock.fixAt(cancelled.expiresAt().minusNanos(1));

        // When
        assertThatThrownBy(() -> naive.cancel(member(2), cancelled.id())).isInstanceOf(NotFoundException.class);
        assertThat(mvc.delete().uri("/naive/reservations/{id}?memberEmail=member01@library.test", cancelled.id()))
                .hasStatus(HttpStatus.NO_CONTENT);

        // Then
        assertThat(status(cancelled)).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(copy("N0001").getStatus()).isEqualTo(CopyStatus.AVAILABLE);
        assertThat(activeItems(1)).isEqualTo(1);          // only N0002 is left
        clock.fixAt(late.expiresAt());
        assertThatThrownBy(() -> naive.cancel(member(1), late.id()))
                .isInstanceOf(ReservationNotActiveException.class);
        assertThat(sweep.sweepOnce().expired()).isEqualTo(1);   // the late one, not the cancelled one
        assertThat(notices.sentFor(cancelled.id())).isZero();
    }

    @Test
    void returningACollectedCopyPutsItBackAndARetryChangesNothing() {
        // Given
        ReservationResponse reservation = reserve(1, "N0001");
        UUID loan = naive.collect(member(1), reservation.id()).loanIds().getFirst();
        clock.advance(java.time.Duration.ofDays(3));
        Instant returnedAt = clock.instant();

        // When
        LoanResponse first = returns.giveBack(loan);
        clock.advance(java.time.Duration.ofMinutes(1));
        LoanResponse retry = returns.giveBack(loan);

        // Then
        assertThat(first.returned()).isTrue();
        assertThat(retry).isEqualTo(first);
        assertThat(retry.returnedAt()).isEqualTo(returnedAt);
        assertThat(copy("N0001").getStatus()).isEqualTo(CopyStatus.AVAILABLE);
        assertThat(loans.countOpenByCopy(copyId("N0001"))).isZero();
        assertThat(mvc.post().uri("/naive/loans/{id}/return", loan)).hasStatusOk()
                .bodyJson().extractingPath("$.returned").isEqualTo(true);
    }

    @Test
    void aSweepRunHandlesOnePageAndLeavesTheRestForTheNext() {
        // Given: three expired reservations, a page of two
        List<ReservationResponse> expired = List.of(reserve(1, "N0001"), reserve(2, "N0002"), reserve(3, "N0003"));
        clock.fixAt(expired.getFirst().expiresAt());
        ExpirySweep smallPages = new ExpirySweep(reservations, naive, clock, 2);

        // When / Then
        assertThat(smallPages.sweepOnce()).isEqualTo(new ExpirySweep.SweepResult(2, 2, 0));
        assertThat(smallPages.sweepOnce()).isEqualTo(new ExpirySweep.SweepResult(1, 1, 0));
        assertThat(smallPages.sweepOnce()).isEqualTo(new ExpirySweep.SweepResult(0, 0, 0));
        assertThat(expired).allSatisfy(r -> assertThat(notices.sentFor(r.id())).isEqualTo(1));
    }

    private ReservationResponse reserve(int member, String... barcodes) {
        return reserving.reserve(member(member), lookup.copyIds("North", List.of(barcodes)));
    }

    private UUID member(int n) {
        return lookup.memberId("member%02d@library.test".formatted(n));
    }

    private UUID copyId(String barcode) {
        return lookup.copyIds("North", List.of(barcode)).getFirst();
    }

    private Copy copy(String barcode) {
        return copies.findById(copyId(barcode)).orElseThrow();
    }

    private ReservationStatus status(ReservationResponse reservation) {
        return reservations.findById(reservation.id()).orElseThrow().getStatus();
    }

    private long activeItems(int member) {
        return reservations.countActiveItems(member(member), clock.instant());
    }
}
