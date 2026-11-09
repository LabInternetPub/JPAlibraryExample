package cat.tecnocampus.library.expiry;

import cat.tecnocampus.library.TestcontainersConfiguration;
import cat.tecnocampus.library.domain.CopyStatus;
import cat.tecnocampus.library.domain.ReservationStatus;
import cat.tecnocampus.library.loan.NaiveLoanReturnService;
import cat.tecnocampus.library.scenario.ScenarioRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The naive versions really do the damage each scenario claims, on every run. These assertions
 * describe the bug, not the requirement: the requirement is asserted by {@link ExpiryRaceTest}.
 *
 * <p>Not {@code @Transactional}: every call commits in its own transaction on its own thread.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExpiryNaiveScenariosTest {

    @Autowired ExpiryRaces races;
    @Autowired SweepRaces sweeps;
    @Autowired NaiveExpiryTransitions naive;
    @Autowired CopiesFirstExpiryTransitions copiesFirst;
    @Autowired OneTransactionSweep oneTransaction;
    @Autowired NaiveLoanReturnService naiveReturn;
    @Autowired ScenarioRegistry registry;

    @Test
    void everyExpiryScenarioIsRegistered() {
        assertThat(registry.all()).filteredOn(s -> s.lesson().equals("expiry")).extracting("name")
                .containsExactlyInAnyOrder("collect-expiry-naive", "cancel-stale-naive", "sweep-after-rereserve-naive",
                        "sweep-deadlock-naive", "sweep-two-workers-naive", "expiry-notice-rollback-naive",
                        "sweep-one-transaction", "return-double-scan-naive");
    }

    @Test
    void collectionCommitsFirstAndExpiryOverwritesIt() {
        ExpiryRaces.Round round = races.collectionCommitsFirst(naive::collect, naive);

        assertThat(round.collect()).isEqualTo("200 collected");
        assertThat(round.expire()).isEqualTo("true");
        assertThat(round.facts().reservation()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(round.facts().copy()).isEqualTo(CopyStatus.AVAILABLE);
        assertThat(round.facts().openLoans()).isEqualTo(1);      // a loan of a copy on the shelf
        assertThat(round.facts().notices()).isEqualTo(1);
    }

    @Test
    void expiryCommitsFirstAndCollectionOverwritesIt() {
        ExpiryRaces.Round round = races.expiryCommitsFirst(naive::collect, naive);

        assertThat(round.collect()).isEqualTo("200 collected");
        assertThat(round.expire()).isEqualTo("true");
        assertThat(round.facts().reservation()).isEqualTo(ReservationStatus.COLLECTED);   // looks right
        assertThat(round.facts().copy()).isEqualTo(CopyStatus.AVAILABLE);                 // is not
        assertThat(round.facts().openLoans()).isEqualTo(1);
        assertThat(round.facts().notices()).isEqualTo(1);
    }

    @Test
    void aStaleCancellationUndoesACommittedCollection() {
        ExpiryRaces.CancelRound round = races.staleCancel(naive::cancel, naive::collect);

        assertThat(round.collect()).isEqualTo("200 collected");
        assertThat(round.cancel()).isEqualTo("204 cancelled");
        assertThat(round.facts().reservation()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(round.facts().copy()).isEqualTo(CopyStatus.AVAILABLE);
        assertThat(round.facts().openLoans()).isEqualTo(1);
    }

    @Test
    void aLateSweepFreesTheCopyOfANewerReservation() {
        ExpiryRaces.LateSweep late = races.lateSweep(naive);

        assertThat(late.anna()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(late.bruno()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(late.copy()).isEqualTo(CopyStatus.RESERVED);           // by Clara, now
        assertThat(late.copyHeldBy()).isEqualTo("someone else");
        assertThat(late.claraReserves()).isEqualTo("201 reserved");
        assertThat(late.liveReservationsOfTheCopy()).isEqualTo(2);       // Bruno and Clara
    }

    @Test
    void locksInOppositeOrdersDeadlockAndPostgresAbortsOne() {
        ExpiryRaces.DeadlockRound round = races.deadlock(copiesFirst);

        assertThat(Stream.of(round.reserve(), round.expire()).filter(o -> o.contains("40P01"))).hasSize(1);
    }

    @Test
    void twoWorkersExpireEveryReservationTwiceAndNotifyTwice() {
        SweepRaces.TwoWorkers workers = sweeps.twoWorkers(naive, 10);

        assertThat(workers.expireReturnedTrue()).isEqualTo(20);
        assertThat(workers.outcome().storedExpired()).isEqualTo(10);    // the rows look right
        assertThat(workers.outcome().reservationsNotifiedTwice()).isEqualTo(10);
    }

    @Test
    void aNoticeSentBeforeARollbackStaysSent() {
        SweepRaces.NoticeRollback rollback = sweeps.noticeThenRollback(naive);

        assertThat(rollback.sweep()).isEqualTo(new ExpirySweep.SweepResult(3, 2, 1));
        assertThat(rollback.outcome().storedActive()).isEqualTo(1);
        assertThat(rollback.outcome().notifiedButNotExpired()).isEqualTo(1);
    }

    @Test
    void oneTransactionSweepLetsBrunoWaitAndRollsBackEverythingOnOneFailure() {
        SweepRaces.Backlog backlog = sweeps.backlog(oneTransaction::sweepAll, 100);

        assertThat(backlog.outcome().storedExpired()).isEqualTo(100);
        assertThat(backlog.brunoReserves()).isEqualTo("201 reserved");
        assertThat(backlog.afterAFailure()).isEqualTo("injected failure");
        assertThat(backlog.outcomeAfterAFailure().storedExpired()).isZero();
        assertThat(backlog.outcomeAfterAFailure().notifiedButNotExpired()).isEqualTo(51);   // 0..50, the failing one included
    }

    @Test
    void aRetriedReturnErasesTheReservationMadeInBetween() {
        ExpiryRaces.ReturnRound round = races.doubleReturn(naiveReturn::giveBack, naive::collect);

        assertThat(List.of(round.firstReturn(), round.secondReturn())).containsOnly("200 returned");
        assertThat(round.brunoReserves()).isEqualTo("201 reserved");
        assertThat(round.brunosReservation()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(round.copyHeldBy()).isNotEqualTo("Bruno");
        assertThat(round.claraReserves()).isEqualTo("201 reserved");
    }
}
