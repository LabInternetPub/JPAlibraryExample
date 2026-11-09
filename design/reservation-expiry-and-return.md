# Lesson 6: Reservation expiry, collection and return — naive and better

This is the plan for the library version of Affora's UC-17 (*hold expiry*). The requirements are in
[ReservationExpiryReturn.md](ReservationExpiryReturn.md), and the Affora reference solution is in
[uc17.md](uc17.md). The lesson follows the same rules as lessons 1–5
([concepts.md](concepts.md), [domain-and-examples.md](domain-and-examples.md)): each concept gets a
**naive** version and a **better** version, side by side in the code, and each version has its own
**scenario** that prints the same facts.

**Status:** implemented in five stages on branch `expiry-lesson` (§9). The scenario sections (§4)
were written before the code; the measured results are in §2 (one block per stage) and in
`design/evidence/`, and where a measurement contradicted a prediction, §4 says so.

When this lesson starts, lessons 4 and 5 are already correct: a copy cannot be promised twice, and
a member cannot go over 5 items. Everything that goes wrong here is a new race between **two
operations on the same reservation** (or the same loan). Fixing lessons 4 and 5 did not fix it.

---

## 1. Mapping from Affora to the library

| Affora (UC-17) | Library | Notes |
| --- | --- | --- |
| Hold, 10 minutes | `Reservation`, 48 hours (`Reservation.DURATION`) | Already exists |
| Seat `HELD` by a hold, with a deadline | `Copy` `RESERVED`, with `reservationId` and `reservedUntil` | Already exists |
| Confirm a hold | **Collect** a reservation: the member picks the copies up at the desk. One `Loan` per copy, copies `ON_LOAN`, reservation `COLLECTED` | New. Like confirmation, it removes the deadline: the loan replaces it |
| Expire a hold (sweep) | **Expire** a reservation: `EXPIRED`, copies `AVAILABLE`, a notice on the console with the member's email and the barcodes | New |
| Release a hold (`DELETE /holds/{id}`) | **Cancel** a reservation (`DELETE /reservations/{id}`) | New |
| Per-customer quota row | Member limit (5 items), protected by locking the `Member` row (lesson 5) | The count is computed, not stored |
| Catalog's available counter | None: the shelf map computes availability at read time (`Copy.visibleStatusAt(now)`) | Design choice, see §8 |
| Seat map shows an expired `HELD` seat as free | Shelf map shows an expired `RESERVED` copy as `AVAILABLE` | Already exists (lesson 3) |
| Claim may take an expired `HELD` seat | `Copy.reserve` may take an expired `RESERVED` copy | Already exists (lesson 4) |
| Payment after confirmation (D4) | Returning a copy (`POST /loans/{id}/return`) | New, a second pair of transitions on one row |

---

## 2. Domain changes

| Change | Where | Why |
| --- | --- | --- |
| `ReservationStatus.EXPIRED` | enum | Expiry is now a stored state, not only computed |
| `Reservation.collect(now)` → `COLLECTED`; throws `ReservationNotActiveException` unless `isLiveAt(now)` | `Reservation` | The time rule stays in the domain and takes `now` |
| `Reservation.expire(now)` → `boolean`: `true` only for the caller that must release the copies | `Reservation` | "Exactly once" starts here: a second call returns `false` |
| `Reservation.cancel(member, now)` → `CANCELLED`; throws unless live and owned | `Reservation` | Release by the member |
| `Copy.collect(reservationId, now)`: `RESERVED` by **this** reservation and before `reservedUntil` → `ON_LOAN` | `Copy` | `Copy.lend(now)` refuses a reserved copy, even for its own reservation |
| `Copy.releaseFrom(reservationId)`: back to `AVAILABLE` only if it is still held by that reservation | `Copy` | A late sweep must not free a copy that another reservation took (scenario S4) |
| `Copy.collect` and `releaseFrom` refuse with `CopiesUnavailableException` / return `false`; `ReservationNotActiveException` → `409 /problems/reservation-not-active` | `Copy`, `ProblemDetailsHandler` | Clean rejections |
| `Loan.forCollection(member, copy, now)`: records a copy already put on loan, without calling `Copy.lend` again | `Loan` | Collection (S2) |
| `Loan.giveBack(now)` returns whether this call closed the loan, and records `returnedAt`; a second call is a no-op, not an exception | `Loan` | Idempotent return (scenario S8) |

**Status:** stage 1 is implemented (`MovableClock`, the changes above, and the unit tests
`ReservationExpiryTest`, `CopyCollectTest`, `LoanTest`, `MovableClockTest`).

**Stage 2 is implemented** (naive transitions and naive sweep, sequential test green):

| Piece | Where |
| --- | --- |
| `NaiveExpiryTransitions`: `collect` (time read first, plain read, `lendOwned` count ignored), `expire` (plain read, `Copy.makeAvailable`, notice before commit), `cancel` (`findById` then `lockById`: stale) | `expiry` |
| `ReservationExpirer` (interface the sweep depends on), `ExpirySweep` (not a bean: bounded page, one transaction per candidate, a failure counts and is skipped), `ExpirySweepScheduler` (`@Scheduled(fixedDelay)`, `@ConditionalOnProperty`) | `expiry` |
| `ExpiryNotices` (logs the notice, counts per reservation) | `expiry` |
| `NaiveLoanReturnService` (load, `giveBack`, dirty checking) | `loan` |
| `POST /naive/reservations/{id}/collection` (body `{"memberEmail"}`), `DELETE /naive/reservations/{id}?memberEmail=`, `POST /naive/loans/{id}/return` | `ExpiryController`, `LoanController` |
| `GET /reservations/{id}` → `410 /problems/reservation-expired` once `Reservation.isExpiredAt(now)`; the response now has `status` | `ReservationLookupService` |
| `ReservationRepository.findExpiredIds(now, page)`, `lockById`; `CopyRepository.lendOwned`; `LoanRepository.countOpenByCopy`; index `ix_reservation_expiry (status, expires_at)` | `persistence`, `Reservation` |
| `Copy.makeAvailable()`: the naive, unconditional release | `Copy` |
| Sweep off in tests: `src/test/resources/config/application.properties` | tests |

**Decision found while testing: the clock has the database's precision.** `MovableClock.instant()`
is truncated to microseconds. With nanoseconds, the PostgreSQL driver rounds `deadline − 1 ns` up to
the deadline, so the Java rule (`now < expiresAt`: live) and the SQL rule (`expires_at <= :now`:
expired) disagreed about the same instant: the sweep picked a live reservation and the member
count stopped counting it. The same happens in production with a nanosecond system clock (Linux).
It is the "keep the boundary identical in both places" rule of option B, one level lower: the same
comparison, at the same precision. In scenarios, "one nanosecond before the deadline" is therefore
one microsecond before it.

**Stage 3 is implemented** (pause points and the naive scenarios):

| Piece | Where |
| --- | --- |
| `TransitionPauses`: `stopAt(stage, key)` (one thread stops until resumed), `meetAt(stage, n)` (n threads meet, one stage only), `failAt(stage, key)` (throws there). Stages: `collect-read`, `collect-validated`, `expiry-read`, `expiry-copies-locked`, `expiry-notified`, `cancel-read`, `return-read`, `reserve-member-locked`, `sweep-candidates-read` | `scenario` |
| `CopiesFirstExpiryTransitions` (S5: right in everything except the lock order), `OneTransactionSweep` (S7) | `expiry` |
| `ExpiryDemo` (fresh start, threads, outcomes, facts), `ExpiryFixtures` (hundreds of reservations on Central), `ExpiryRaces` (S2, S3, S4, S5, S8), `SweepRaces` (S6, S6b, S7): the drivers take the operations as functions, so stage 4 reuses them | `expiry` |
| `ExpiryScenarios`: eight scenarios, lesson `expiry` | `expiry` |
| `DemoClockController`: `GET/POST/DELETE /demo/clock?plusHours=…` | `scenario` |
| `ExpiryNaiveScenariosTest` (each naive scenario does the damage it claims), `ExpiryRaceTest` (the mandatory test; fails on naive, captured in `design/evidence/expiry-naive-race.log`, then `@Disabled`) | tests |

Decisions taken in stage 3:

- **The scheduled sweep is off by default** (`library.expiry.sweep-enabled=false`). Central's example
  data has 100 reservations already expired but stored as `RESERVED`, which the shelf-map lesson
  shows as available; a background sweep erased that example a minute after the start.
- **Scenarios run 30 days in the past.** `ExpiryDemo.start()` fixes the clock 30 days back, so a
  scenario's sweep never reaches Central's example reservations (checked after running all eight:
  500 `RESERVED`, 100 of them expired, every reader reservation still `ACTIVE`).
- **S5 uses stop points, not `RaceWindow`.** A second `RaceWindow` call inside
  `MemberLockingReservationService` would break lesson 5 (a thread holding the member lock would
  wait at the barrier for threads waiting for that lock).
- **S6 uses 100 reservations** (one page), and the notice-then-rollback case is its own scenario,
  `expiry-notice-rollback-naive`. **S7 uses a backlog of 1,000.**
- `DemoReset.resetNorthAndScenarioMembers` now also releases the copies (any branch) held or lent
  by scenario members.

**Measured** (one run of the eight scenarios over HTTP on a freshly started application, Apple M3
Pro; reports in `design/evidence/expiry-naive-scenarios.json`):

| Scenario | Rounds | Result, every round |
| --- | --- | --- |
| `collect-expiry-naive`, collection first | 10 | collect `200`, expire `true`; `EXPIRED`, copy `AVAILABLE`, 1 open loan, 1 notice |
| `collect-expiry-naive`, expiry first | 10 | collect `200`, expire `true`; `COLLECTED`, copy `AVAILABLE`, 1 open loan, 1 notice |
| `cancel-stale-naive` | 5 | cancel `204`, collect `200`; `CANCELLED`, copy `AVAILABLE`, 1 open loan |
| `sweep-after-rereserve-naive` | 1 | Anna `EXPIRED`, Bruno `ACTIVE` but holding nothing, Clara `201`: 2 live promises for N0002 |
| `sweep-deadlock-naive` | 5 | the sweep aborted with `40P01` every time; Anna's reservation `201` |
| `sweep-two-workers-naive` | 100 reservations | `expire()` true 200 times, 100 stored `EXPIRED`, **200 notices**, every member notified twice |
| `expiry-notice-rollback-naive` | 3 reservations | 2 expired, 1 failed and still `ACTIVE`, 3 notices: 1 member told about an expiry that did not happen |
| `sweep-one-transaction` | 1,000 reservations | 8.0 s, 3,061 statements + 1,999 batches, 2,060 entities loaded, 94 MB heap peak. Bruno, reserving a released copy during the sweep, **hit the 5 s lock timeout** (`55P03` → `503`). One failure in the middle: **0 expired, 501 notices sent** |
| `return-double-scan-naive` | 5 | both returns `200`, Bruno `201` in between, his reservation `ACTIVE` but the copy not his; Clara `201` |

In S7 the prediction was "Bruno waits for the whole sweep". He waits up to the lock timeout and then
gets `503`: the sweep (8 s) holds the copy longer than any request is allowed to wait.

**Stage 4 is implemented** (the better versions; `ExpiryRaceTest` re-enabled and green on both
options; 132 tests, none skipped):

| Piece | Where |
| --- | --- |
| `LockingExpiryTransitions` (option A, the reference): `transitionKey` projection first; locks **member → reservation → copies**; clock read after the locks; `Reservation` loaded for the first time under its lock; `Copy.collect` / `releaseFrom` on locked copies; expiry takes the member and the reservation with `SKIP LOCKED` and returns `false` instead of waiting | `expiry` |
| `ConditionalExpiryTransitions` (option B): member locked first (lock order), then `collectIfLive` / `expireIfDue` decide by affected rows; `lendOwned`'s count checked; `releaseOwned` may legitimately return 0 | `expiry` |
| `ExpiryNoticeListener`: `@TransactionalEventListener(AFTER_COMMIT)`; the better transitions publish the notice as an event | `expiry` |
| `ConditionalLoanReturnService`: `closeIfOpen` (loan row decides), `shelveIfOnLoan` (status column only, only if `ON_LOAN`); a retry answers `200` with the same loan | `loan` |
| Repositories: `ReservationKey` projection, `transitionKey`, `lockIfFree` (native `FOR UPDATE SKIP LOCKED`) on reservation and member, `findCopyIds`, `collectIfLive`, `expireIfDue`, `releaseOwned`, `shelveIfOnLoan`, `findBarcodesOfReservation`, `findCopyId`, `closeIfOpen` | `persistence` |
| `ExpirySweepScheduler` now uses option A; `SweepRaces.untilDone` runs bounded sweeps until nothing is due | `expiry` |
| Endpoints: `POST /better/reservations/{id}/collection`, `DELETE /better/reservations/{id}?memberEmail=`, `POST /better/loans/{id}/return` | controllers |
| Nine better scenarios: `collect-expiry-locked`, `collect-expiry-conditional`, `cancel-locked`, `sweep-after-rereserve-owned`, `sweep-ordered`, `sweep-two-workers-skip-locked`, `expiry-notice-after-commit`, `sweep-batched`, `return-conditional` | `ExpiryScenarios` |
| `ExpiryBetterScenariosTest`; `ExpiryRaceTest` on A and B | tests |

Decisions taken in stage 4:

- **`SKIP LOCKED`, not `NOWAIT`, for the member.** `NOWAIT` throws, and in PostgreSQL a failed
  statement aborts the whole transaction: the sweep could not even return `false` cleanly.
  `SKIP LOCKED` returns no row, and the sweep skips.
- **The deadlock round now tries expiry again afterwards** (`expireAgain`), to show that skipping is
  safe: the next run expires the reservation.
- **Option A releases copies through the domain** (`lockCopies` + `Copy.releaseFrom`), keeping the
  lock order (copies last); option B uses `releaseOwned`.
- **Notices are "at most once".** After-commit sending never tells a member about an expiry that
  rolled back, but a crash between commit and send loses the notice. Exactly once would need an
  outbox table written in the same transaction; that is beyond this lesson.

**Measured** (one run over HTTP on a freshly started application, same machine; reports in
`design/evidence/expiry-better-scenarios.json`), side by side with stage 3:

| Scenario pair | Naive (stage 3) | Better |
| --- | --- | --- |
| S2 collect vs expiry, collection first (10 rounds) | both succeed; `EXPIRED`, copy `AVAILABLE`, 1 loan, 1 notice | A and B: collect `200`, expire `false`; `COLLECTED`, copy `ON_LOAN`, 1 loan, 0 notices |
| S2 expiry first (10 rounds) | both succeed; `COLLECTED`, copy `AVAILABLE`, 1 loan | A and B: collect `409`, expire `true`; `EXPIRED`, copy `AVAILABLE`, 0 loans, 1 notice |
| S3 stale cancel (5) | `204`; `CANCELLED`, copy on the shelf, 1 open loan | `409`; `COLLECTED`, copy `ON_LOAN` |
| S4 late sweep | copy freed; Bruno holds nothing; Clara `201`; 2 live promises | copy stays Bruno's; Clara `409`; 1 live promise |
| S5 lock order (5) | sweep aborted with `40P01` | sweep skipped (`false`), Anna `201` without waiting, next run expires |
| S6 two workers, 100 reservations | 200 `true`, 200 notices | 100 `true` (51 + 49), 100 notices |
| S6b failure after the notice | 1 member told about an expiry that rolled back | 2 notices for 2 expiries; the failed one sent nothing |
| S7 backlog of 1,000 | 6.2–8.0 s, one transaction; Bruno `503` after 5 s; one failure: 0 expired, 501 false notices | 12.4 s in 11 runs of ≤ 100; Bruno `201` after 19 ms; one failure: retried by the next run, 1,000 expired, 1,000 notices |
| S8 retried return (5) | Bruno's reservation erased; Clara `201` | Bruno keeps the copy; retry `200`; Clara `409` |

**S7 differs from the prediction.** The bounded sweep is about **twice as slow in total** (5,011
statements against 3,061: key, member lock, reservation lock, copy locks per reservation, and 1,000
commits), and its heap peak is not lower (90 MB against 86 MB; the number is noisy and mostly
garbage). At 1,000 reservations the one-transaction sweep's dirty checking does not hurt yet. What
the bounded sweep buys is not throughput: nobody waits behind it, a failure costs one reservation,
and no notice is sent for an expiry that did not happen. The table of §4 S7 predicted otherwise for
time and memory; this measurement replaces it.

The boundary is decided once, as in Affora: a reservation is live while `now < expiresAt`. At
`expiresAt − 1 ns` collecting succeeds; at `expiresAt` collecting fails and expiring succeeds.
There is no instant at which both are allowed, and none at which neither is.

```java
public boolean isLiveAt(Instant now) { return status == ACTIVE && now.isBefore(expiresAt); }   // exists

public void collect(Instant now) {
    if (!isLiveAt(now)) throw new ReservationNotActiveException(id);
    status = ReservationStatus.COLLECTED;
}

/** Returns true only for the caller that must release the copies and print the notice. */
public boolean expire(Instant now) {
    if (status != ReservationStatus.ACTIVE || now.isBefore(expiresAt)) return false;
    status = ReservationStatus.EXPIRED;
    return true;
}
```

These domain methods are the same in the naive and the better versions. **The bug is never in the
rule. It is in how many transactions get to apply the rule to the same row, and on which data.**

### 2.1 The invariant, stated before any code

For every reservation, **exactly one** of these wins, and its effects happen **exactly once**:

| Winner | Reservation | Copies | Loans | Member's active items | Console notice |
| --- | --- | --- | --- | --- | --- |
| collection | `COLLECTED` | `ON_LOAN`, no `reservationId` | +n | unchanged (n reserved → n on loan) | none |
| expiry | `EXPIRED` | `AVAILABLE` (unless another reservation has taken them since) | 0 | −n | exactly one |
| cancellation | `CANCELLED` | `AVAILABLE` | 0 | −n | none |

And the answer the caller got must match what was committed: a collection that returned `200`
must own its loans **and** its copies after everything has committed.

---

## 3. Infrastructure needed

| Piece | What it does | Concept |
| --- | --- | --- |
| `MovableClock` (replaces the `Clock` bean in `ClockConfiguration`) | Delegates to the system clock; a scenario or a test can set a fixed instant or move it forward | Controllable time: no scenario waits 48 hours |
| `TransitionPauses` (new, next to `RaceWindow`) | Named pause points (`collect-read`, `collect-validated`, `expiry-read`, `cancel-read`, `return-read`). Does nothing until a scenario arms one stage with a pair of latches ("I'm here" / "go on") | Forcing **one specific ordering**. `RaceWindow` makes N threads meet, but it cannot say "this one commits first" |
| `ExpiryTransitions` (bean) | `collect`, `expire`, `cancel`, each `@Transactional`, in its naive and better versions | Transitions on a different bean from the worker: no self-invocation |
| `ExpirySweep` (bean, `@ConditionalOnProperty("library.expiry.sweep-enabled")`) | `@Scheduled(fixedDelayString = "${library.expiry.sweep-delay:60000}")`, calls `ExpiryTransitions.expire(id)` for a bounded page of candidates. Exposes `sweepOnce()` for scenarios | The scheduled process of the requirements |
| `ExpiryNotices` | Prints `Reservation <id> expired: <email> <barcodes>`, and keeps a count per reservation that the scenarios read | "Effects exactly once" made visible |
| Index `ix_reservation_expiry (status, expires_at)` | Makes the candidate query an index range scan | Bounded, cheap sweep |
| `ProblemDetailsHandler` | `ReservationNotActiveException` → `409 /problems/reservation-not-active`; `GET /reservations/{id}` → `410 Gone` once `now ≥ expiresAt` | Clean rejections; lazy expiry on read |

The scheduler is **off by default** (`library.expiry.sweep-enabled=false`) and in tests (a test
asserts the bean is absent): scenarios move the clock, and a timer running behind them would expire
their fixtures at an unpredictable moment; it would also expire Central's example reservations that
the shelf-map lesson relies on. Scenarios call `sweepOnce()` themselves. To show the real timer, start
the application with `--library.expiry.sweep-enabled=true --library.expiry.sweep-delay=5000`, create a
reservation, move the clock with `POST /demo/clock?plusHours=48`, and watch the console.

---

## 4. The scenarios

Every concurrency scenario follows the protocol of `uc17.md` §4.3:

- fresh fixture per round (one member, one or two copies on North), **ten rounds** per ordering;
- each operation on its own thread, committing in its own transaction;
- after both have really committed, the scenario reads **all the facts** of the invariant again,
  through fresh queries: reservation status, copy status and `reservationId`, open loans, the member's
  active items, notices printed;
- the report has one line per round, plus a summary row like the tables below.

The "expected results" below were written before the code. Every one of them was confirmed by the
measured runs (§2, stage 3 and stage 4 blocks), except where a **Measured** note says otherwise.

### S1 · The boundary, one operation at a time (`expiry-boundary`)

**Story.** Anna reserves copy N0001 at 10:00 on Monday. Her reservation expires at 10:00 on
Wednesday. The scenario moves the clock and calls the operations **one after the other**:

| Clock | Operation | Expected |
| --- | --- | --- |
| Wed 10:00 − 1 ns | shelf map | N0001 `RESERVED` |
| Wed 10:00 − 1 ns | collect | `200`, 1 loan, N0001 `ON_LOAN` |
| Wed 10:00 (fresh fixture) | shelf map | N0001 `AVAILABLE` (lazy, the row is still `RESERVED`) |
| Wed 10:00 | `GET /reservations/{id}` | `410 Gone`, the row still says `ACTIVE` |
| Wed 10:00 | collect | `409 reservation-not-active` |
| Wed 10:00 | Anna's active items | 0 (the count already ignores it) |
| Wed 10:00 | sweep, twice | first: `EXPIRED`, 1 notice; second: nothing |
| Thu (after collecting) | sweep | the collected reservation is not a candidate |

**The point.** The naive and the better versions both pass this scenario. **Sequential tests cannot
see the bug** of S2, because it only exists when two transactions overlap. Show this one first,
all green, and then break it.

**Concepts:** the invariant before the code; the time rule in the domain, with `now` as a
parameter; the exact boundary (`<` versus `<=`); an injected, movable clock; lazy expiry on reads
(shelf map, `410`, member count) without any write; collection removes the deadline (the loan
replaces it).

### S2 · Collect at the deadline, while the sweep expires (`collect-expiry-*`)

**Story.** Anna arrives at the desk at 09:59:59.999 on Wednesday. The librarian scans her
reservation. At the same moment the sweep, whose clock already reads 10:00, picks the same
reservation.

**Naive version** (`NaiveExpiryTransitions`). Every method is `@Transactional`, and the rule is in
the domain. Many teams would stop here:

```java
@Transactional
public CollectionResponse collect(UUID memberId, UUID reservationId) {
    Instant now = clock.instant();                                   // (1) time read first
    Reservation r = reservations.findById(reservationId)             // (2) plain read, no lock
            .filter(x -> x.getMember().getId().equals(memberId)).orElseThrow(...);
    pauses.observe("collect-read", reservationId);
    r.collect(now);                                                  // (3) decision in Java
    pauses.observe("collect-validated", reservationId);
    copies.lendOwned(reservationId);                                 // (4) bulk UPDATE, count ignored
    r.getCopyIds().forEach(id -> loans.save(new Loan(r.getMember(), copies.getReferenceById(id), now)));
    return CollectionResponse.from(r);
}

@Transactional
public boolean expire(UUID reservationId) {
    Instant now = clock.instant();
    Reservation r = reservations.findById(reservationId).orElseThrow(...);   // plain read, no lock
    pauses.observe("expiry-read", reservationId);
    if (!r.expire(now)) return false;
    copies.findAllById(r.getCopyIds()).forEach(Copy::makeAvailable);
    notices.expired(r);
    return true;
}
```

(`lendOwned` is `update Copy c set c.status = ON_LOAN, c.reservationId = null, c.reservedUntil = null
where c.reservationId = :id`: the author has learned conditional updates in lesson 4, but does not
check what it returns. The `Loan` constructor is changed for collection so that it does not call
`Copy.lend` again.)

**Forced orderings** (with `TransitionPauses`):

*Collection commits first.* Clock at deadline − 1 ns. Collection pauses at `collect-validated`; the
clock moves to the deadline; expiry reads the reservation (still `ACTIVE`: collection has not
committed) and pauses at `expiry-read`; collection commits; expiry continues on its `ACTIVE` copy.

*Expiry commits first.* Collection reads `now` and the reservation and pauses at `collect-read`; the
clock moves to the deadline; expiry runs and commits; collection continues with its cached `now`.

**Expected result:**

| Ordering | collect | expire | Reservation | Copy | Loans | Active items | Notices |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Naive, collection first | `200` | `true` | `EXPIRED` | `AVAILABLE` | 1 | 1 | 1 |
| Naive, expiry first | `200` | `true` | `COLLECTED` | `AVAILABLE` | 1 | 1 | 1 |
| **Required**, collection first | `200` | `false` | `COLLECTED` | `ON_LOAN` | 1 | 1 | 0 |
| **Required**, expiry first | `409` | `true` | `EXPIRED` | `AVAILABLE` | 0 | 0 | 1 |

In both naive rows **both operations reported success**. Anna walks out with the book and an open
loan, the system shows the copy `AVAILABLE` on the shelf map, the next member can reserve it, and
Anna gets an e-mail saying her reservation expired. The second row is the worst: the reservation
*says* `COLLECTED`, so a test that only checks the reservation status passes. Only the copy, the
loans and the notices show the damage.

**Why it fails** (show the sequence diagrams of `uc17.md` §2.4 a with these names, and the SQL log):

1. **Lost update on the reservation row.** Hibernate's dirty-checking statement is
   `update reservation set status=?, … where id=?`. It has no condition on the old status, so the
   database re-checks nothing and the second writer overwrites the first one's decision. Lesson 4's
   defect in a new place: a correct domain rule decided on stale data.
2. **`@Transactional` is not arbitration.** Under `READ COMMITTED` a plain `SELECT` takes no lock and
   sees only what was committed before it ran.
3. **Time read too early.** `collect` reads the clock before reading the reservation. If it then
   waits, it decides with an old "now". Read the time **after** you have the right to decide.
4. **An ignored row count.** In the expiry-first ordering `lendOwned` updated 0 rows, which means
   "you no longer own any copy". The code went on and created a loan for a copy on the shelf.

**Better version A: one row is the referee, with a pessimistic lock** (`LockingExpiryTransitions`,
the reference):

```java
@Transactional
public CollectionResponse collect(UUID memberId, UUID reservationId) {
    // Scalars only: a managed Reservation loaded here would stay stale after the lock (S3).
    ReservationKey key = reservations.transitionKey(reservationId)
            .filter(k -> k.memberId().equals(memberId)).orElseThrow(...);
    Member member = members.lockById(memberId).orElseThrow();        // lock 1: member (lesson 5)
    Reservation r = reservations.lockById(reservationId).orElseThrow(...);   // lock 2: read fresh
    Instant now = clock.instant();                                   // after the locks
    r.collect(now);                                                  // current status, current time
    List<Copy> held = copies.lockCopies(r.getCopyIds());             // lock 3: copies, id order
    List<Loan> loans = held.stream().map(c -> {
        c.collect(reservationId, now);                               // throws if not held by r
        return Loan.forCollection(member, c, now);
    }).toList();
    loans.forEach(entityManager::persist);
    return new CollectionResponse(...);
}

@Transactional
public boolean expire(UUID reservationId) {
    ReservationKey key = reservations.transitionKey(reservationId).orElse(null);
    if (key == null) return false;
    pauses.observe("expiry-read", reservationId);
    // Never wait behind a member at the desk: skip, a later run sees the reservation again.
    Member member = members.lockIfFree(key.memberId()).orElse(null);         // FOR UPDATE SKIP LOCKED
    if (member == null) return false;
    Reservation r = reservations.lockIfFree(reservationId).orElse(null);     // FOR UPDATE SKIP LOCKED
    if (r == null || !r.expire(clock.instant())) return false;
    List<Copy> held = copies.lockCopies(r.getCopyIds());             // lock 3: copies
    held.forEach(c -> c.releaseFrom(reservationId));                 // only if still held, see S4
    events.publishEvent(new ExpiryNotice(...));                      // sent after the commit, see S6
    return true;
}
```

**Better version B: a conditional `UPDATE` decides** (`ConditionalExpiryTransitions`, the lesson 4
pattern, equally acceptable):

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("""
       update Reservation r set r.status = COLLECTED
       where r.id = :id and r.member.id = :member and r.status = ACTIVE and r.expiresAt > :now
       """)
int collectIfLive(UUID id, UUID member, Instant now);

@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("update Reservation r set r.status = EXPIRED where r.id = :id and r.status = ACTIVE and r.expiresAt <= :now")
int expireIfDue(UUID id, Instant now);
```

One row: you won, do the side effects in the same transaction and check each of their counts.
Zero rows: someone else decided first, or the time rule says no. It works because under
`READ COMMITTED` PostgreSQL makes the second `UPDATE` wait and then **re-evaluates the `WHERE`** on
the committed row. The cost: the time rule now lives twice (`isLiveAt` and the `WHERE`), with the
same boundary (`>` versus `<=`), pinned by S1. The member must still be locked **before** the
conditional update, to keep the lock order of S5.

**Concepts:** lost update between two transitions; `READ COMMITTED`; dirty-checking `UPDATE … WHERE
id = ?`; one row as the referee; pessimistic lock versus conditional `UPDATE`; fresh time after the
lock; every affected-row count checked or the transaction throws; `@Modifying` with
`clearAutomatically` / `flushAutomatically`; forced interleavings with pause points; asserting all
the facts after commit.

### S3 · The stale cancellation (`cancel-stale-naive`, `cancel-locked`)

**Story.** Anna collects her reservation at the desk. At the same moment her phone, on a slow
connection, sends the "cancel reservation" request she tapped a minute ago.

**Naive version.** The author has learned from S2 and "added a lock":

```java
@Transactional
public void cancel(UUID memberId, UUID reservationId) {
    Reservation r = reservations.findById(reservationId).orElseThrow(...);  // loads the entity
    if (!r.getMember().getId().equals(memberId)) throw new NotFoundException(...);
    pauses.observe("cancel-read", reservationId);
    reservations.lockById(reservationId);                     // SELECT … FOR UPDATE: waits, then...
    r.cancel(memberId, clock.instant());                      // ...decides on the SAME old object
    copies.findAllById(r.getCopyIds()).forEach(Copy::makeAvailable);
}
```

**Forced ordering.** Cancel pauses at `cancel-read`; collection runs and commits; cancel continues.

**Expected result:** naive: cancel `204`, reservation `CANCELLED`, copy `AVAILABLE`, 1 open loan.
Anna has the book, and the system has put it back on the shelf. Better: cancel `409`, reservation
`COLLECTED`, copy `ON_LOAN`.

**Why it fails.** The lock is real, but `lockById` returns the **same Java object** the persistence
context already holds. Acquiring a row lock does not refresh its fields. The scenario report shows
it: the SQL log has the `FOR UPDATE`, and still the decision used `ACTIVE`. (Not built as a
scenario step: printing `r.getStatus()` after the lock, then after `entityManager.refresh(r)`, is a
good live addition in the debugger.)

**Better.** Before the lock, read only scalar keys (`transitionKey`, a projection): the owner check
needs them, and they leave the persistence context empty. The entity is loaded for the first time
**under** the lock. `refresh(r, PESSIMISTIC_WRITE)` also works, but it is easy to forget for the
next field someone adds.

**Concepts:** the first-level cache; why `SELECT … FOR UPDATE` does not refresh a managed entity;
projection before arbitration, entity only under the lock.

### S4 · The late sweep frees someone else's copy (`sweep-after-rereserve-*`)

**Story.** Anna's reservation of N0002 expires at 10:00. Nobody has swept yet. At 10:05 Bruno
reserves N0002: `Copy.reserve` accepts it, because an expired reservation no longer holds the copy
(lesson 4, lazy expiry on writes). The row now says `RESERVED` by Bruno until Friday. At 10:06 the
sweep finally reaches Anna's reservation.

This one is **sequential**: no pause points. It is the price of lazy expiry, not a race.

**Naive sweep:** for each copy id of Anna's reservation, `copy.makeAvailable()`.

**Expected result:** naive: N0002 `AVAILABLE`, `reservationId = null`; Bruno got `201` and his
reservation is `ACTIVE`, but no copy is held for him, so Clara can reserve it too. A lost promise
without any concurrency. Better: `releaseOwned` updates 0 rows for N0002, the copy stays Bruno's,
Anna's reservation is `EXPIRED` and she gets her notice.

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("""
       update Copy c set c.status = AVAILABLE, c.reservationId = null, c.reservedUntil = null
       where c.reservationId = :reservation and c.status = RESERVED
       """)
int releaseOwned(UUID reservation);
```

Here 0 rows is a normal result, not an error: the sweep must not throw, and the report shows how
many copies were released and how many had already been taken over.

**Concepts:** lazy expiry versus sweep versus both; "lazy" also covers writes that happen anyway
(the reservation takes over an expired copy); the sweep only does bookkeeping, correctness comes
from every reader and writer applying the time rule; release only what you still own; an
affected-row count that may legitimately be 0.

### S5 · Expiry against a new reservation of the same member (`sweep-deadlock-naive`, `sweep-ordered`)

**Story.** Anna's old reservation of N0003 has just expired. Anna, at home, reserves N0003 and N0004
again through `MemberLockingReservationService` (lesson 5: member, then copies). At the same instant
the sweep expires her old reservation.

**Naive version.** The sweep locks in the order that looks natural from its own point of view: the
reservation, then its copies, then the member (to "update the limit").

**Forced ordering.** Each thread takes the lock the other one will need (the reservation service:
Anna's member row; the sweep: copy N0003) and stops there (`TransitionPauses` stop points
`reserve-member-locked` and `expiry-copies-locked`; a `RaceWindow` call inside
`MemberLockingReservationService` would have broken lesson 5). Both are then resumed, and each asks
for the lock the other holds. After both finish, the round runs the expiry once more
(`expireAgain`), as the next sweep would.

**Expected result:** naive: PostgreSQL detects the cycle and aborts one transaction with
`40P01 deadlock detected` after `deadlock_timeout` (1 s). If it is Anna's, she gets a `500` (or the
mapped `503`) for a request that had nothing wrong with it. Better: one lock order everywhere
(**member → reservation → copies → loans**) for reserve, collect, cancel and expire; the scenario
reports 0 deadlocks. In addition the better sweep uses `SKIP LOCKED` on the member and the
reservation, so it gives up instead of waiting: Anna never waits behind the sweep.

**Measured** (5 rounds each): naive, the sweep was the one aborted with `40P01` every round, and Anna
got `201`; better, the sweep returned `false` at once (Anna's request held the member), Anna got
`201`, and `expireAgain` expired the old reservation.

**Concepts:** lock order; a deadlock is detected and costs an abort and a second, not a hang; the
worker never waits (`FOR UPDATE SKIP LOCKED`; `NOWAIT` would throw and abort the PostgreSQL
transaction); why skipping is safe (the reservation stays
`ACTIVE` and expired in the database, the next run sees it again) and what it costs (bookkeeping is
late, not wrong).

### S6 · Two application instances sweep at once (`sweep-two-workers-*`)

**Story.** The library runs two instances behind a load balancer. Each has its own scheduler, so
both sweeps start at 10:00 on the same 100 expired reservations (one page each).

**Naive version.** The S2 naive `expire`, called by two `sweepOnce()` in two threads. Both read
`ACTIVE`, both decide in Java, both write `EXPIRED`.

**Forced ordering.** The two sweeps meet (`TransitionPauses.meetAt`) after reading their candidates
and after reading each reservation, so both read it before either writes.

**Measured:**

| Version | Reservations expired | `expire` returned `true` | Notices printed | Max notices for one reservation |
| --- | --- | --- | --- | --- |
| Naive | 100 | 200 | 200 | 2 |
| Better (`SKIP LOCKED`) | 100 | 100 (51 + 49) | 100 | 1 |

The final rows look right in both versions: `EXPIRED` twice is still `EXPIRED`, and an `AVAILABLE`
copy made `AVAILABLE` again does not change. **Only the side effects show the bug**: every member
received two e-mails. With a counter (a stored available count, or Affora's quota) the counter would
be wrong too.

**S6b, the notice and the transaction** (its own scenarios, `expiry-notice-rollback-naive` and
`expiry-notice-after-commit`). The naive version prints the notice inside the transaction. Of three
expired reservations, the second fails right after its notice (`TransitionPauses.failAt`), so its
transaction rolls back: the member was told "expired" about a reservation that is
still `ACTIVE`. The better version publishes an event and prints from a
`@TransactionalEventListener(phase = AFTER_COMMIT)`: a rolled-back expiry prints nothing. This is the
library's version of Affora's "charged, but no ticket": something left the system that the database
does not confirm.

**Concepts:** `fixedDelay` only prevents overlap inside one scheduler; two instances need database
arbitration; effects exactly once; side effects outside the database after commit; final state
versus side effects as evidence.

### S7 · One big sweep versus a bounded sweep (`sweep-one-transaction`, `sweep-batched`)

**Story.** The sweep was disabled for a weekend. On Monday there are 1,000 expired reservations.
While the sweep runs, Bruno reserves the copy of the first one, which the sweep has already
released. Then the same backlog again, with one reservation in the middle failing.

**Naive version** (`OneTransactionSweep`). Typically written as
`@Scheduled(fixedRate = 60_000) @Transactional public void sweep()`; here a scenario calls it. Every
expired reservation, unpaged, each through the naive `expire`, which **joins** the sweep's
transaction (propagation `REQUIRED`):

```java
@Transactional
public SweepResult sweepAll() {
    for (UUID id : reservations.findExpiredIds(clock.instant(), Pageable.unpaged()))
        transitions.expire(id);                    // joins this transaction: nothing commits until the end
}
```

**Expected result** (the prediction; see **Measured** below):

| Fact | Naive | Better (100 per page, one transaction each) |
| --- | --- | --- |
| Rows read by the candidate query | all active reservations | at most 100, `ix_reservation_expiry` |
| Entities in the persistence context at the end | ~5,000 reservations + copies + members | ≤ ~3 per transaction |
| Statements | N+1 for members and copies (to print the notice) | one projection per page for the notice data |
| Median wait of a desk collection during the sweep | the whole sweep (locks kept until the final commit) | milliseconds |
| One poisoned reservation (hook throws) | everything rolls back, 0 expired | 4,999 expired, the failing one stays a candidate |
| Runs needed for 5,000 | 1 (if it survives) | 50 runs; "60 s" is the interval, not the maximum delay |

**Measured** (1,000 reservations; stage 3 and 4 blocks of §2): the naive sweep took 6.2–8.0 s,
3,061 statements + 1,999 batches; Bruno **timed out after 5 s** (`55P03`, a `503`) instead of
waiting to the end; one failure left 0 expired and 501 notices sent. The bounded sweep took
**12.4 s** in 11 runs (5,011 statements + 2,000 batches), Bruno got `201` after 19 ms, and the
failing reservation was retried by the next run (1,000 expired, 1,000 notices). The heap peak was
about the same (86 MB against 90 MB, noisy). So the prediction was right about waiting and
failures, and wrong about time and memory: at this size the bounded sweep is about twice as slow.

Two traps to show in the code of the better version:

- **Self-invocation.** `ExpirySweep.sweepOnce()` calls `transitions.expire(id)` on **another bean**.
  (Not built as a scenario: a variant calling `this.expire(id)` would get no transaction per
  candidate, and its `@Modifying` queries would fail with "no transaction in progress". Show it in
  the code.)
- **`fixedRate` versus `fixedDelay`.** With `fixedRate`, a run longer than the interval is followed
  immediately by the next one; with `fixedDelay` the delay starts when the previous run ends.

**Concepts:** bounded batch ordered by `expiresAt`; index for the candidate query; one transaction
per candidate; why one big `@Transactional` is worse (locks, rollback of everything, persistence
context growth from lesson 1, N+1 from lesson 2); self-invocation; `fixedDelay` versus `fixedRate`.

### S8 · Returning a copy twice (`return-double-scan-naive`, `return-conditional`)

**Story.** Anna drops N0005 into the self-service return machine. The machine times out waiting for
the answer and retries the same request. Between the two, Bruno, who saw N0005 come back on the
shelf map, reserves it.

**Naive version.** `POST /loans/{id}/return` loads the loan and its copy, calls `loan.giveBack()`,
and lets dirty checking write. Both calls read the loan as open before either commits.

**Forced ordering.** Both returns pause at `return-read`; the first commits; Bruno reserves N0005 and
commits; the second return continues.

**Expected result:** naive: both returns `200`; the second one's `Copy` update writes **every column**
of its stale object (`status = AVAILABLE, reservation_id = null, reserved_until = null`), so Bruno's
reservation no longer holds the copy. Better: the second return finds the loan already closed and
answers `200` with the **same** result (an idempotent retry, not a `409` and not a second effect);
N0005 stays `RESERVED` by Bruno.

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("update Loan l set l.returned = true, l.returnedAt = :now where l.id = :id and l.returned = false")
int closeIfOpen(UUID id, Instant now);

@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("update Copy c set c.status = AVAILABLE where c.id = :copy and c.status = ON_LOAN")
int shelveIfOnLoan(UUID copy);
```

`closeIfOpen` returns 1 for the first call (then `shelveIfOnLoan` must return 1, or throw) and 0 for
the retry, which just reads and returns the closed loan. As an optional extension, the loan's
`dueAt` gives a second time boundary: a return at exactly `dueAt` is late, tested like S1.

**Concepts:** the same lost update on a different row (the loan); Hibernate updates all columns by
default, so a stale entity overwrites columns it never changed (`@DynamicUpdate` would narrow it, it
would not fix it); idempotent retries; a conditional transition with its count checked.

---

## 5. Concept coverage

Every concept of `uc17.md`, and where the library shows it.

| Concept (from `uc17.md`) | § in uc17 | Scenario(s) |
| --- | --- | --- |
| State the invariant (one winner, effects once) before the code | 1.1 | S1, S2 |
| Time rule in the domain, `now` as a parameter, exact boundary | 1.2 | S1 |
| Injected, controllable clock | 1.2, 4 | all (`MovableClock`) |
| Sequential tests cannot see the race | 2.2 | S1 vs S2 |
| Lost update between two transitions; dirty-checking `UPDATE … WHERE id = ?` | 2.4 b | S2, S6, S8 |
| `@Transactional` and `READ COMMITTED` do not arbitrate | 2.4 b | S2 |
| Time read too early | 2.4 c | S2 (expiry-first ordering) |
| Ignored affected-row count | 2.4 c | S2, S4, S8 |
| Stale managed entity after a lock | 2.4 d | S3 |
| Usual reflexes that do not fix it | 2.4 e | §6 below; one big transaction in S7 |
| One row as the referee, pessimistic lock | 3.2 | S2 (A), S3 |
| Scalar projection before the lock | 3.2 | S3, S2 (A) |
| Fresh time after the lock | 3.2 | S2 |
| Same lock order everywhere | 3.2 | S5 |
| The worker never waits: `SKIP LOCKED` (`NOWAIT` rejected, §2 stage 4) | 3.2 | S5, S6 |
| Conditional `UPDATE`, `@Modifying` flags, duplicated time rule | 3.3 | S2 (B), S4, S8 |
| Lazy expiry on reads (no write) | 3.5 | S1 (shelf map, `410`, member count) |
| Lazy expiry on writes that happen anyway | 3.5 | S4 (reservation takes an expired copy) |
| The sweep does bookkeeping, not correctness | 3.5 | S4, S5 |
| Do not double-count when lazy and sweep overlap | 3.5 | S4 (release only owned copies); no stored counter, §8 |
| A run longer than the interval: `fixedDelay` vs `fixedRate` | 3.6 | S7 |
| Two instances at once | 3.6 | S6 |
| How many rows per run: bounded batch, index, one transaction each | 3.6 | S7 |
| Self-invocation | 3.6 | S7 |
| Tests must not race the real worker | 3.6 | §3, tests |
| Confirmation removes the deadline instead of extending it | 3.7 | S1 (the loan replaces the deadline) |
| Something left the system that the database does not confirm ("charged, no ticket") | 1, 3.7 | S2 (expiry e-mail to a member holding the book), S6b (notice after commit) |
| Forced interleavings with pause points, both orderings, ten rounds | 4.3 | S2, S3, S8 |
| No `@Transactional` on the test; assert every fact after commit | 4.3 | all concurrency scenarios and tests |

---

## 6. Reflexes that do not fix S2

Show this table after S2, before the better version.

| Reflex | Why it is not enough |
| --- | --- |
| `synchronized` on `collect` and `expire` | Protects one JVM. S6 runs two instances. It also serializes every reservation, not just this one |
| `SERIALIZABLE` isolation | Detects the conflict and aborts one, but then every transition needs a retry loop |
| Check the status again just before saving | Still a Java `if` on data read without a lock: the window is smaller, not closed |
| Sweep less often | Changes how often the race happens, not whether it can |
| One `@Transactional` around the whole sweep | Worse: S7 |
| `findById` then `lockById` | The lock does not refresh the object: S3 |

---

## 7. Tests (examples, not the demonstration)

| Test | Kind | What it checks |
| --- | --- | --- |
| `ReservationExpiryTest` | unit, no Spring | `collect`, `expire`, `cancel` one nanosecond before and exactly at `expiresAt`; a second expiry returns `false`; a collected reservation cannot expire or be cancelled. If it needed a database, the rule would be in the wrong layer |
| `CopyCollectTest` | unit | `Copy.collect` only for the reservation that holds the copy and before `reservedUntil`; `releaseFrom` leaves a copy another reservation has taken |
| `LoanTest`, `MovableClockTest` | unit | idempotent `giveBack`, `forCollection`; the clock stops, moves, resets, and truncates (never rounds up) to microseconds |
| `ExpiryTransitionTest` | sequential integration, naive | S1 (the boundary, `410`, lazy expiry on reads, the sweep twice), cancellation, return, the sweep's page bound (3 candidates, pages of 2), the scheduler bean absent in tests |
| `ExpiryRaceTest` | concurrency, the mandatory test | S2 both orderings × 10 fresh fixtures, on options A and B; every fact after both commits. It failed on the naive version (stage 3), captured in `design/evidence/expiry-naive-race.log`, then was disabled until stage 4 |
| `ExpiryNaiveScenariosTest` | concurrency | every naive driver (S1–S8) does exactly the damage its scenario claims, on every run; every scenario is registered |
| `ExpiryBetterScenariosTest` | concurrency | every better driver keeps the invariant: S1 boundary on A and B, S3, S4 and S6 and S6b on A and B, S5 (skip, then expire), S7 (bounded sweep drains 250 in 4 runs; a failure is retried), S8 |

Three rules, from `uc17.md` §4.3: no `@Transactional` on concurrency tests; assert every fact of
the invariant after both have committed, not only the reservation status or the return values;
ten fresh fixtures per ordering, and both orderings.

---

## 8. Design decisions to record

- **Lazy and sweep, both.** The shelf map, `GET /reservations/{id}`, the member count and
  `Copy.reserve` apply `now < expiresAt` themselves. The sweep brings the **stored** status in line
  and prints the notice. Without the sweep the system is still correct for copies and the limit, but
  no notice is ever printed and the stored status stays `ACTIVE` forever.
- **No stored availability counter.** Affora keeps one for the catalog, and has to make sure that a
  lazy claim and the sweep do not both add to it. The library computes availability from the copy
  rows at read time, so that trap does not exist. Adding a `Book.availableCopies` counter is a
  possible extension of the lesson: it brings back S4's double-count, this time on a number.
- **Collection removes the deadline.** The copy goes `ON_LOAN` with no `reservedUntil`, and the loan
  carries its own `dueAt` (21 days). A collected reservation can never become "expired" by lazy
  reads.
- **Pessimistic lock (A) as the reference, conditional update (B) also shown.** A keeps the decision
  in `Reservation`; B needs fewer statements and duplicates the time rule in JPQL.
- **The sweep interval is a choice, not a guarantee.** 60 s by default; under contention or with a
  backlog, a reservation can wait several runs.

---

## 9. Commits (naive first), branch `expiry-lesson`

| Stage | Commit | Content |
| --- | --- | --- |
| plan | `b6666b7` | This document, as first written |
| 1 | `c6ba1c9` | `MovableClock`, `ReservationStatus.EXPIRED`, domain methods and unit tests (S1's rule) |
| 2 | `07da9a9` | Naive transitions and naive sweep, `ExpiryTransitionTest` green (sequential) |
| 3 | `f997962` | `TransitionPauses`, naive scenarios S2–S8; `ExpiryRaceTest` captures the failure, then `@Disabled` |
| 4 | `e43dbf4` | Better transitions (A and B), ordered locks, `SKIP LOCKED`, `releaseOwned`, after-commit notices, bounded sweep; tests re-enabled and green |
| 5 | (this commit) | The `expiry-boundary` scenario (S1, missing until then); measured numbers in this document, `concepts.md` and `domain-and-examples.md` updated, `scenarios.http` requests |

## 10. Demonstration order

| Order | Scenario | Main point to show |
| --- | --- | --- |
| 1 | `expiry-boundary` | Every answer right, on the naive version. Ask: "if both collect and expire return true, what does Anna have?" |
| 2 | `collect-expiry-naive` | Both succeed in 10 of 10 rounds, each ordering; the copy is on the shelf map and in Anna's bag |
| 3 | SQL log of round 1 | `update reservation set status=? … where id=?`: no condition on the status |
| 4 | `collect-expiry-locked`, `collect-expiry-conditional` | Exactly one winner, all facts consistent |
| 5 | `cancel-stale-naive` → `cancel-locked` | The lock was taken, the decision was still stale |
| 6 | `sweep-after-rereserve-naive` → `-owned` | No concurrency needed to lose a promise |
| 7 | `sweep-deadlock-naive` → `sweep-ordered` | `40P01` once, then never |
| 8 | `sweep-two-workers-naive` → `-skip-locked` | 200 e-mails → 100 |
| 9 | `expiry-notice-rollback-naive` → `expiry-notice-after-commit` | A notice for an expiry that did not happen → none |
| 10 | `sweep-one-transaction` → `sweep-batched` | Bruno times out after 5 s → 19 ms; one failure undoes 1,000 → costs one; and the bounded sweep is slower in total |
| 11 | `return-double-scan-naive` → `return-conditional` | A retry erased Bruno's reservation → the retry changes nothing |
| 12 | `scenarios.http`, "Expiry endpoints, live" | Reserve, move the clock 48 h, `GET` → `410` while the row says `ACTIVE`, collect → `409` |

## 11. Questions to check understanding

1. In S2's naive version both operations returned success. Which committed state did each one miss,
   and why did PostgreSQL not stop the second write?
2. In S3 the SQL log shows `FOR UPDATE`. Why did the cancellation still decide on `ACTIVE`?
3. Which instant decides a collection: when the librarian scanned, or something else? Why?
4. In S4 there is no concurrency at all. What made the sweep wrong, and what does `releaseOwned`
   returning 0 mean?
5. The better sweep skips a reservation it cannot lock. Why is that safe, and what does it cost?
6. If you remove the sweep entirely, what is still correct and what is now wrong?
7. In S6 the final rows are identical in both versions. What evidence shows the naive version is
   wrong?
8. Why must the expiry notice be printed after commit, and what would a rolled-back expiry have
   told the member otherwise?
9. `fixedDelay` versus `fixedRate`, and why neither protects you from two instances.
10. In S8, why did a retry of a correct return erase a column it never changed?
