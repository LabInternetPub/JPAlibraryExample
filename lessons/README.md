# Lessons

These documents are for students. Each one takes a piece of this library application, shows a
**naive** version that looks correct, measures what goes wrong with it on a real PostgreSQL, and
explains the **better** version down to the SQL.

The documents in [`../design`](../design) were used to design the example. You don't need them to
follow the lessons.

## Before you start

- Start the application: `./mvnw spring-boot:run` (with Docker running: Spring
  Boot starts the PostgreSQL of [`../compose.yaml`](../compose.yaml) for you). The schema and the data are recreated at every start.
- The scenarios are HTTP requests in [`../scenarios.http`](../scenarios.http). Each one prints a
  report, and the SQL log shows every statement sent to the database.

## The documents

| Document | What it is about |
| --- | --- |
| [`reservation-expiry-lessons.md`](reservation-expiry-lessons.md) | The **main document**. Section 1 describes the domain and the data used by every lesson. Then it covers concurrent transitions of the same row (collect vs expire vs cancel vs return): lost updates, row locks, conditional updates, lock order and deadlocks, side effects after the commit, idempotency. |
| [`bulk-import.md`](bulk-import.md) | Importing 20,000 copies from a CSV: why the naive import grows with the square of the rows, JDBC batching, the persistence context and the cost of dirty checking. |
| [`conditional-update-vs-select-lock.md`](conditional-update-vs-select-lock.md) | One small example (returning a loan) solved in the two correct ways, `SELECT … FOR UPDATE` vs a conditional `UPDATE`, side by side. |
| [`collect-expiry-lock-vs-conditional.md`](collect-expiry-lock-vs-conditional.md) | The same two ways on the real code of the project (collect vs expire), with the measured scenarios. |

## Recommended reading order

1. **The domain and the data.** [`reservation-expiry-lessons.md`](reservation-expiry-lessons.md),
   sections **1.1** and **1.2**. Every other document assumes you know the entities (`Book`,
   `Copy`, `Member`, `Loan`, `Reservation`), their rules and the data loaded at startup.

2. **Bulk import.** [`bulk-import.md`](bulk-import.md), the whole document. It involves no
   concurrency, so it's the easiest way into how JPA talks to the database. It introduces the
   persistence context and dirty checking, which the later documents rely on.

3. **The concurrency problem and its background.**
   [`reservation-expiry-lessons.md`](reservation-expiry-lessons.md):
   - sections **1.3–1.6**: what the library wants, the invariant, the time rule, the movable clock;
   - section **2**: transactions in Spring, `READ COMMITTED`, the persistence context, row locks,
     conditional updates. Don't skip it: every lesson refers back to it.

4. **The lessons.** [`reservation-expiry-lessons.md`](reservation-expiry-lessons.md), section **3**,
   in order:
   - Lesson 0 (sequential tests can't see a race) and Lesson 1 (lost update) are the core. Run
     their scenarios.
   - Lessons 2–4: the clock, affected-row counts, stale entities under a lock.
   - Lessons 5–7: lazy expiry, the sweep, lock order and deadlocks.
   - Lessons 8–11: several instances, effects that leave the system, designing the sweep,
     retries and idempotency.

   Each lesson can be read on its own once sections 1 and 2 are done. The order above is the
   gentlest.

5. **The two fixes compared.**
   - [`conditional-update-vs-select-lock.md`](conditional-update-vs-select-lock.md), after
     lesson 11, which tells the story of its example (the retried return).
   - Then [`collect-expiry-lock-vs-conditional.md`](collect-expiry-lock-vs-conditional.md), the
     same comparison on the project's code. Run its two scenarios (`collect-expiry-locked`,
     `collect-expiry-conditional`).

6. **Wrap up.** [`reservation-expiry-lessons.md`](reservation-expiry-lessons.md), sections **4–9**:
   the better solution in one page, reflexes that don't work, how it's tested, the checklist of
   common mistakes, the questions to check your understanding, and the glossary. Try the
   questions before rereading. If one is hard, it points you to the lesson to revisit.
