package cat.tecnocampus.library.expiry;

import cat.tecnocampus.library.MovableClock;
import cat.tecnocampus.library.TestcontainersConfiguration;
import cat.tecnocampus.library.domain.CopyStatus;
import cat.tecnocampus.library.domain.ReservationNotActiveException;
import cat.tecnocampus.library.domain.ReservationStatus;
import cat.tecnocampus.library.loan.ConditionalLoanReturnService;
import cat.tecnocampus.library.reservation.ReservationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The better versions under the same forced interleavings as {@link ExpiryNaiveScenariosTest}:
 * every fact of the invariant agrees with exactly one winner, and every effect happens once.
 *
 * <p>Not {@code @Transactional}: every call commits in its own transaction on its own thread.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExpiryBetterScenariosTest {

    @Autowired ExpiryRaces races;
    @Autowired SweepRaces sweeps;
    @Autowired ExpiryDemo demo;
    @Autowired MovableClock clock;
    @Autowired ExpiryNotices notices;
    @Autowired LockingExpiryTransitions locking;
    @Autowired ConditionalExpiryTransitions conditional;
    @Autowired ConditionalLoanReturnService conditionalReturn;

    @Test
    void oneOperationAtATimeTheBetterVersionsKeepTheBoundary() {
        for (ExpiryRaces.Collect collect : List.<ExpiryRaces.Collect>of(locking::collect, conditional::collect)) {
            // Given
            demo.start();
            try {
                UUID anna = demo.member(1);
                ReservationResponse early = demo.reserve(1, "N0001");
                ReservationResponse late = demo.reserve(1, "N0002");

                // When / Then: the last microsecond before the deadline, then the deadline
                clock.fixAt(early.expiresAt().minus(1, ChronoUnit.MICROS));
                assertThat(collect.apply(anna, early.id()).loanIds()).hasSize(1);
                clock.fixAt(late.expiresAt());
                assertThatThrownBy(() -> collect.apply(anna, late.id())).isInstanceOf(ReservationNotActiveException.class);
                assertThat(demo.facts(early.id(), anna, "N0001").copy()).isEqualTo(CopyStatus.ON_LOAN);
                assertThat(demo.facts(late.id(), anna, "N0002").copy()).isEqualTo(CopyStatus.RESERVED);
            } finally {
                demo.finish();
            }
        }
    }

    @Test
    void aLateCancellationSeesTheCommittedCollection() {
        ExpiryRaces.CancelRound round = races.staleCancel(locking::cancel, locking::collect);

        assertThat(round.collect()).isEqualTo("200 collected");
        assertThat(round.cancel()).isEqualTo("409 not active");
        assertThat(round.facts().reservation()).isEqualTo(ReservationStatus.COLLECTED);
        assertThat(round.facts().copy()).isEqualTo(CopyStatus.ON_LOAN);
        assertThat(round.facts().openLoans()).isEqualTo(1);
    }

    @Test
    void aLateSweepLeavesBrunosCopyAlone() {
        for (ReservationExpirer expirer : List.<ReservationExpirer>of(locking, conditional)) {
            ExpiryRaces.LateSweep late = races.lateSweep(expirer);

            assertThat(late.anna()).isEqualTo(ReservationStatus.EXPIRED);
            assertThat(late.bruno()).isEqualTo(ReservationStatus.ACTIVE);
            assertThat(late.copy()).isEqualTo(CopyStatus.RESERVED);
            assertThat(late.copyHeldBy()).isEqualTo("Bruno");
            assertThat(late.claraReserves()).isEqualTo("409 copies unavailable");
            assertThat(late.liveReservationsOfTheCopy()).isEqualTo(1);
        }
    }

    @Test
    void theSweepSkipsAMemberWhoIsReservingAndTheNextRunExpires() {
        ExpiryRaces.DeadlockRound round = races.deadlock(locking);

        assertThat(round.reserve()).isEqualTo("201 reserved");
        assertThat(round.expire()).isEqualTo("false");          // skipped, no deadlock, nobody waited
        assertThat(round.expireAgain()).isEqualTo("true");
        assertThat(round.oldReservation()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(round.copyHeldBy()).isEqualTo("the new reservation");
    }

    @Test
    void twoWorkersExpireEachReservationOnceWithOneNotice() {
        for (ReservationExpirer expirer : List.<ReservationExpirer>of(locking, conditional)) {
            SweepRaces.TwoWorkers workers = sweeps.twoWorkers(expirer, 10);

            assertThat(workers.expireReturnedTrue()).isEqualTo(10);
            assertThat(workers.outcome().storedExpired()).isEqualTo(10);
            assertThat(workers.outcome().notices()).isEqualTo(10);
            assertThat(workers.outcome().reservationsNotifiedTwice()).isZero();
        }
    }

    @Test
    void aRolledBackExpirySendsNoNotice() {
        for (ReservationExpirer expirer : List.<ReservationExpirer>of(locking, conditional)) {
            SweepRaces.NoticeRollback rollback = sweeps.noticeThenRollback(expirer);

            assertThat(rollback.sweep()).isEqualTo(new ExpirySweep.SweepResult(3, 2, 1));
            assertThat(rollback.outcome().storedActive()).isEqualTo(1);
            assertThat(rollback.outcome().notices()).isEqualTo(2);
            assertThat(rollback.outcome().notifiedButNotExpired()).isZero();
        }
    }

    @Test
    void theBoundedSweepDrainsABacklogAndAFailureCostsOneReservation() {
        SweepRaces.Backlog backlog = sweeps.backlog(() -> SweepRaces.untilDone(sweeps.boundedSweep(locking)), 250);

        assertThat(backlog.sweep()).isEqualTo(new SweepRaces.Drained(4, 250, 250, 0).toString());
        assertThat(backlog.outcome().storedExpired()).isEqualTo(250);
        assertThat(backlog.brunoReserves()).isEqualTo("201 reserved");
        assertThat(backlog.outcomeAfterAFailure().storedExpired()).isEqualTo(250);   // retried by a later run
        assertThat(backlog.outcomeAfterAFailure().notices()).isEqualTo(250);
        assertThat(backlog.outcomeAfterAFailure().notifiedButNotExpired()).isZero();
    }

    @Test
    void aRetriedReturnChangesNothingAndBrunoKeepsTheCopy() {
        ExpiryRaces.ReturnRound round = races.doubleReturn(conditionalReturn::giveBack, locking::collect);

        assertThat(List.of(round.firstReturn(), round.secondReturn())).containsOnly("200 returned");
        assertThat(round.brunoReserves()).isEqualTo("201 reserved");
        assertThat(round.copy()).isEqualTo(CopyStatus.RESERVED);
        assertThat(round.copyHeldBy()).isEqualTo("Bruno");
        assertThat(round.openLoans()).isZero();
        assertThat(round.claraReserves()).isEqualTo("409 copies unavailable");
    }
}
