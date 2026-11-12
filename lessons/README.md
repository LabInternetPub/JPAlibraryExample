# Lessons

These documents are for students. Each one takes a piece of this library application, shows a
**naive** version that looks correct, measures what goes wrong with it on a real PostgreSQL, and
explains the **better** version down to the SQL.

The documents in [`../design`](../design) were used to design the example. You don't need them to
follow the lessons.

## Before you start

- Start the application: `./mvnw spring-boot:run` (with Docker running: Spring Boot starts the
  PostgreSQL of [`../compose.yaml`](../compose.yaml) for you). The schema and the data are
  recreated at every start.
- The scenarios are HTTP requests in [`../scenarios.http`](../scenarios.http). Each one prints a
  report, and the SQL log shows every statement sent to the database.

## The documents

| Document | What it is about |
| --- | --- |
| [`domain-reads-and-reservations.md`](domain-reads-and-reservations.md) | **Start here.** The domain (entities, rules, mapping decisions), the data loaded at startup and how the scenarios work. Then four lessons: the catalogue (N+1, `JOIN FETCH`, projections, `readOnly`), the shelf map (the cost of managed entities, the first-level cache), reserving the last copy (lost update, `SELECT … FOR UPDATE`) and the per-member limit (write skew). |
| [`bulk-import.md`](bulk-import.md) | Importing 20,000 copies from a CSV: why the naive import grows with the square of the rows, JDBC batching, `persist` vs `merge`, the cost of dirty checking. |
| [`reservation-expiry-lessons.md`](reservation-expiry-lessons.md) | Concurrent transitions of the same row (collect vs expire vs cancel vs return): the invariant, the time rule, lost updates between transitions, stale entities, conditional updates, lock order and deadlocks, `SKIP LOCKED`, side effects after the commit, a bounded sweep, idempotency. |
| [`conditional-update-vs-select-lock.md`](conditional-update-vs-select-lock.md) | One small example (returning a loan) solved in the two correct ways, `SELECT … FOR UPDATE` vs a conditional `UPDATE`, side by side. |
| [`collect-expiry-lock-vs-conditional.md`](collect-expiry-lock-vs-conditional.md) | The same two ways on the real code of the project (collect vs expire), with the measured scenarios. |

## Recommended reading order

The order goes from reading data efficiently, to writing it efficiently, to writing it correctly
when several requests arrive at once.

1. **The domain, the data and the scenarios.**
   [`domain-reads-and-reservations.md`](domain-reads-and-reservations.md), sections **1–3**. Every
   other document assumes you know the entities (`Book`, `Copy`, `Member`, `Loan`, `Reservation`),
   their rules and the data loaded at startup.

2. **Reading efficiently.**
   [`domain-reads-and-reservations.md`](domain-reads-and-reservations.md):
   - section **4**, the catalogue: lazy loading, the N+1 problem, `JOIN FETCH`, projections,
     `readOnly`. It introduces the persistence context, which everything after builds on.
   - section **5**, the shelf map: the first-level cache, and why two statements can still be too
     slow.

3. **Writing efficiently.** [`bulk-import.md`](bulk-import.md), the whole document: auto-flush,
   dirty checking and JDBC batching, with no concurrency yet.

4. **Writing correctly under concurrency: the first races.**
   [`domain-reads-and-reservations.md`](domain-reads-and-reservations.md):
   - section **6**, reserving the last copy: check-then-act, the lost update, `SELECT … FOR UPDATE`,
     lock order, lock timeout;
   - section **7**, the per-member limit: write skew, and locking one row that represents the set.

   Then sections **8** (tests) and **9** (questions).

5. **Transitions of a reservation.**
   [`reservation-expiry-lessons.md`](reservation-expiry-lessons.md):
   - sections **1** and **2**: the problem (invariant, time rule, movable clock) and the background
     (transactions in Spring, `READ COMMITTED`, the persistence context, row locks, conditional
     updates). Section 2 summarises what sections 4–7 above showed in practice. Don't skip it: every
     lesson refers back to it.
   - section **3**, the lessons, in order. Lesson 0 (sequential tests can't see a race) and
     Lesson 1 (lost update) are the core, so run their scenarios. Then lessons 2–4 (the clock,
     affected-row counts, stale entities under a lock), 5–7 (lazy expiry, the sweep, lock order and
     deadlocks) and 8–11 (several instances, effects that leave the system, designing the sweep,
     retries and idempotency). Each lesson can be read on its own, but this order is the gentlest.

6. **The two fixes compared.**
   - [`conditional-update-vs-select-lock.md`](conditional-update-vs-select-lock.md), after lesson 11
     of the previous document, which tells the story of its example (the retried return).
   - Then [`collect-expiry-lock-vs-conditional.md`](collect-expiry-lock-vs-conditional.md), the same
     comparison on the project's code. Run its two scenarios (`collect-expiry-locked`,
     `collect-expiry-conditional`).

7. **Wrap up.** [`reservation-expiry-lessons.md`](reservation-expiry-lessons.md), sections **4–9**:
   the better solution in one page, reflexes that don't work, how it's tested, the checklist of
   common mistakes, the questions to check your understanding, and the glossary. Try the questions
   before rereading. If one is hard, it points you to the lesson to revisit.
