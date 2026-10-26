# Deliverable 2 concepts, illustrated with a library

The domain and the plan of each example are in [domain-and-examples.md](domain-and-examples.md).

This repository is a small Spring Boot application used to teach the concepts of Affora's
Deliverable 2 (performance and contention). It uses a **different domain**, a public library, so it
does not hand students the Affora solution.

Each concept is implemented twice, **side by side in the code**: a **naive** version that works for
one user and a little data, and a **better** version. Each version has its own **scenario**. The
professor demonstrates the scenarios by running the application, and both scenarios print the
same measurements so the numbers can be compared.

**Technical choices**

- JPA and Hibernate for all application persistence: no `JdbcTemplate` and no JDBC code. Native SQL
  in a Spring Data repository is allowed but kept to a minimum. Where it appears, a comment says
  why JPQL is not enough.
- PostgreSQL runs in Docker (`compose.yaml`) when the application runs. Testcontainers is used only
  by the tests.
- The example data is loaded by SQL scripts when the application starts.
- Tests exist as examples of how each concept is tested. They are not the demonstration.

## 1. Mapping from Affora to the library

| Affora (Deliverable 2) | Library example | Concept it carries |
| --- | --- | --- |
| UC-10 Bulk import of a 20,000-seat map | Import a branch's catalogue: 20,000 copies from a CSV (`isbn, barcode, floor, shelf, condition`) | Write-side N+1, `merge` vs `persist`, auto-flush, dirty checking, persistence-context growth, batching |
| UC-11 Efficient catalog | `GET /books`: each book with its author and its subjects | N+1 read, lazy loading, `JOIN FETCH`, DTO projections |
| UC-12 Seat map of a performance | Shelf map of a branch: status of every copy (available, on loan, reserved, withdrawn) | Entities vs scalar projections, read-only transactions, first-level cache, response size, expiry computed at read time, privacy |
| UC-14 Temporary seat hold | Reserve copies for 48 h. Fifty members try to reserve the last copy of a bestseller at the same instant | Lost update, check-then-act, pessimistic lock, lock order, rollback |
| UC-15 Per-person ticket limit | At most 5 active items (loans plus live reservations) per member | Write skew, phantoms, turning the limit into a conflict on one lockable row |

## 2. Running and demonstrating

### 2.1 Data

- **Schema:** Hibernate creates the schema (`ddl-auto`). The SQL scripts run after it
  (`spring.jpa.defer-datasource-initialization=true`, `spring.sql.init.mode=always`).
- **`data.sql`:** loads the base data:
  - branches, members and authors;
  - 300 books with **distinct authors**, because a single shared author would let the first-level
    cache hide the N+1;
  - one branch with 20,000 copies, generated in SQL so the file stays small;
  - one bestseller with a single available copy.
- **Import file:** the 20,000-row CSV for the bulk import is a resource file. It is not loaded by
  SQL, because importing it is the lesson.
- **Reset before each scenario:** every scenario first restores the data it uses to a known state
  (delete what earlier runs created). That way, the naive and the better scenario run on identical
  data and can be run in any order, any number of times.

### 2.2 Scenarios

A scenario is a class that prepares its data, calls the **application service** of one version
(naive or better), measures, and prints a short report. There are two ways to start one:

- **From the command line:** a `CommandLineRunner` runs the scenarios listed in a property, e.g.
  `./mvnw spring-boot:run -Dspring-boot.run.arguments=--scenario=reservation-naive`. Without the
  property, it runs nothing.
- **From the editor:** a `ScenarioController` exposes `POST /scenarios/{name}` and returns the
  report as JSON. `scenarios.http` holds one request per scenario, in lesson order. The professor
  uses this file during the class.

The use-case endpoints exist for both versions (`/naive/...` and `/better/...`). The `.http` file
also has requests to call them directly, so the class can see the response itself (for example
the size of the shelf map, or the absence of member data in it).

| Lesson | Naive scenario | Better scenario |
| --- | --- | --- |
| Bulk import | `import-naive` | `import-batched` |
| Catalogue | `catalogue-naive` | `catalogue-join-fetch`, `catalogue-projection` |
| Shelf map | `shelf-map-naive` | `shelf-map-projection` |
| Reservation | `reservation-naive` | `reservation-pessimistic` |
| Member limit | `member-limit-naive` | `member-limit-locked` |

### 2.3 What a scenario reports

- **SQL statements**, read from Hibernate `Statistics` (`hibernate.generate_statistics=true`):
  - prepared statements and entity loads;
  - collection fetches;
  - and, for the import, the number of JDBC batches.

  Statistics are cleared at the start of the measurement. The SQL log is left on, so the class
  can read the statements while the scenario reports the count.
- **Time:** a few warm-up calls, then several samples. The report gives the median, not a single
  run.
- **Memory:** heap used before and after. The report says that this number is noisy.
- **Response size** in bytes, for the read cases.
- **For concurrency cases:**
  - successful requests;
  - clean rejections;
  - unexpected errors;
  - the rows that ended up in the database.

  The report counts both the reservation rows and the copy rows, because one reserved copy row can
  hide fifty promises.
- **Each sample in its own transaction**, so the first-level cache does not answer the second
  sample.

**Query plans:** the professor runs `EXPLAIN (ANALYZE, BUFFERS)` from `psql` (`docker compose exec
postgres psql …`) on the statement that the log shows.

### 2.4 Forcing the race in concurrency scenarios

A concurrency scenario starts fifty tasks with an `ExecutorService`. The tasks wait on a
`CountDownLatch` and are released together.

The naive version is still not wrong every time, because the threads drift apart. To fix that,
each service calls a `RaceWindow` bean between its read and its write. In normal operation the
bean does nothing. The concurrency scenarios switch it to a `CyclicBarrier`, so every thread has
read before any thread writes, and the bad interleaving happens on every run. Explain to the class
that the barrier makes a possible schedule visible. It does not measure how often that schedule
happens in real traffic.

## 3. Cross-cutting concepts

### 3.1 Transactions in Spring

- **`@Transactional` boundaries:** calling a `@Transactional` method from the same bean does not
  start a transaction (self-invocation).
- **`readOnly = true`:** the Hibernate session becomes read-only and the flush mode becomes manual,
  so Hibernate keeps no snapshots. Cheaper, not free.
- **Rollback rule:** a runtime exception rolls the transaction back. Returning an error response
  does not. This is how "a rejected request leaves no trace" is achieved.
- **`open-in-view=false`:** lazy data must be loaded inside the service transaction, otherwise it
  fails with `LazyInitializationException`. That is one reason services return DTOs.

### 3.2 Concurrency

- **PostgreSQL `READ COMMITTED`:**
  - a plain `SELECT` takes no lock;
  - an `UPDATE` waits for another transaction's row lock, then re-evaluates its `WHERE` clause on
    the committed row.
- **Errors map to HTTP:**
  - a rejection is `409 Conflict` as `application/problem+json` (`ProblemDetail`), with a field
    that lists what was unavailable;
  - a lock timeout is mapped too, never left as a `500`.

## 4. The five lessons

### 4.1 Bulk import (write-side N+1)

**Scenario.** Import a 20,000-row CSV into a branch. Two copies of the same branch cannot share a
barcode. Importing the same file again updates the copies instead of duplicating them, and keeps
their ids. A bad row anywhere means nothing is imported. Both scenarios import the file twice: a
first import and a re-import.

**Naive (`NaiveCopyImportService`).** For each row:

1. `findByBranchIdAndBarcode(...)`;
2. create the copy or update it;
3. `repository.save(copy)`.

Everything runs in one transaction. This version is correct, idempotent and atomic.

**What the numbers show, and why.** There are four mechanisms:

1. **One to three statements per row:** the lookup, the hidden `SELECT` of `merge`, and the
   `INSERT`.
2. **`save()` behaves as `merge()` for an assigned id.** The UUID is assigned in the constructor,
   so Spring Data's `isNew()` sees a non-null id and calls `merge`, which first selects the row by
   id.
3. **Auto-flush defeats batching, and the cost grows as O(n²).** The lookup query reads the table
   where the previous insert is still pending, so flush mode `AUTO` flushes before every lookup.
   Every batch then holds a single insert. Every flush also dirty-checks all the managed copies,
   and there are more of them at every iteration. That is why the re-import is almost as slow as
   the first import, with a third of the statements.
4. **The persistence context keeps every copy until the transaction ends**, so memory grows with
   the file. A copy that is persisted, not loaded, does not count as an entity load. The entity
   load count can therefore say 0 while the heap grows.

**Better (`BatchedCopyImportService`, JPA only).**

| Naive cause | Fix |
| --- | --- |
| One lookup per row | **One** JPQL query that loads `barcode → id` for the whole branch, as scalars in a record |
| `save()` → `merge()` | `entityManager.persist()` for new copies |
| Auto-flush and O(n²) dirty checking | No query inside the chunk loop. Set `hibernate.jdbc.batch_size`, `order_inserts` and `order_updates`, and add `reWriteBatchedInserts=true` to the driver URL |
| Persistence context growth | `flush()` + `clear()` every 500 rows |

Writes still go through the `Copy` entity (`copy.reimport(...)`). Dirty checking therefore issues
an `UPDATE` only for the rows that changed.

**Points to explain:**

- The whole file is validated before anything is written, which keeps the import atomic. Committing
  in chunks instead would lose atomicity.
- `GenerationType.IDENTITY` would disable insert batching. Assigned UUIDs do not.
- Two simultaneous imports for the same branch can both decide that a barcode is new. The second
  then fails on the unique constraint. Document this, or lock the branch row during the import.

### 4.2 Catalogue listing (read-side N+1)

**Scenario.** `GET /books` returns every book with its title, the author's name and nationality,
and its subjects.

**Naive (`NaiveCatalogueService`).** `findAll()`. Then, for each book, `book.getAuthor().getName()`
and `book.getSubjects()`.

**What the numbers show, and why.**

- **Lazy loading.** Mark `@ManyToOne` as `LAZY` explicitly: JPA's default for it is `EAGER`.
  Collections (`@OneToMany`, `@ManyToMany`) are `LAZY` by default. A lazy association is loaded the first time the code touches
  it, with its own query, so the listing costs 1 + N + N statements.
- **The first-level cache hides part of the N+1.** With one shared author, the author query runs
  only once. The data uses distinct authors for that reason. To show the effect, run the scenario
  once against a set of books that share an author.
- **`EAGER` is not the fix.** It moves the N+1 somewhere else instead of removing it, and it then
  applies to every use of the entity.

**Better.**

1. **`JOIN FETCH`** (`catalogue-join-fetch`): a repository `@Query` that fetches the books with
   their author and their subjects in one statement.
2. **DTO projection** (`catalogue-projection`): `select new ...BookRow(...)` when the response is
   read-only. No managed entities at all.

The professor compares the three scenarios: statements, entity loads and median time.

**Alternatives to discuss:**

- `@EntityGraph(attributePaths = …)`: a declarative `JOIN FETCH`.
- `hibernate.default_batch_fetch_size`: one `SELECT` plus a few `IN` queries, with no change to the
  code.

### 4.3 Shelf map of a branch (projection and response size)

**Scenario.** `GET /branches/{id}/shelf-map` returns the status of each of the 20,000 copies so
that a kiosk can draw the shelves. The statuses are available, on loan, reserved and withdrawn.

- It **never reveals who has borrowed or reserved a copy, nor until when**.
- A reservation whose deadline has passed shows as available, even if nothing has cleaned it up yet.

**Naive (`NaiveShelfMapService`).** Load the `Copy` entities and their `Book`s, then map each copy
to a DTO, with one JSON object per copy. It already returns a DTO, never the entity.

**What the numbers show, and why.**

- **The statement count is low (2–3). The cost is elsewhere:**
  - more than 20,000 managed entities and their identity map;
  - their snapshots, unless the transaction is `readOnly`;
  - then 20,000 DTOs.

  That is three layers of objects for the same data.
- **The JSON repeats itself:** the same field names and values appear 20,000 times.
- **Report the measurements honestly:** state exactly which numbers are bad and do not invent
  others.

**Better (`ProjectedShelfMapService`).**

- **One query, scalars only:** a JPQL constructor expression that returns a record with exactly the
  needed values, joined in one query and ordered by floor and shelf.
- **Expiry in the query:** `case when c.status = RESERVED and c.reservedUntil <= :now then
  'AVAILABLE' … end`, using the request's clock.
- **Privacy by construction:** the query never selects the member or the deadline, and the
  projection has no field for them.
- **Compact format:** copies grouped by floor and shelf, with parallel arrays, one-letter status
  strings and a legend.

**Concepts to explain:**

- **Persistence context / first-level cache:** in one transaction, two `findById` calls for the
  same id emit one statement and return the same object. The cache ends with the transaction.
- **Why a second-level cache is a bad idea here:** statuses change constantly, so entries are
  invalidated almost as soon as they are stored, and every write pays for the invalidation.
- **Projections vs entities:** compare entities with `readOnly`, interface projections and record
  projections.

### 4.4 Reserving the last copy (lost update)

**Scenario.** `POST /naive/reservations` or `POST /better/reservations` with a list of `copyIds`
and a member. A reservation lasts 48 h.

- **All or nothing:** if any requested copy is unavailable, the whole request is rejected and
  **leaves no trace**.
- **Success:** `201` with a `Location` header, an `id` and an `expiresAt`.
- **Rejection:** `409` problem+json with `unavailableCopies`.

The concurrency scenario sends fifty requests from fifty members for the last copy of the
bestseller. A second scenario requests three copies, one of them already reserved.

**Naive (`NaiveReservationService`, check-then-act).**

1. Read the copies and check `isAvailableAt(now)` in Java.
2. Create the reservation and call `copy.reserve(...)`.
3. Let dirty checking write the change at commit.

It works perfectly with one user at a time, and the availability rule even lives in the domain.

**What the scenario shows, and why.**

- **Many `201`s for one copy:** fifty every run, with the race window. There are fifty
  reservation rows but only **one** copy row marked reserved.
- **This is a lost update.** All fifty reads see the copy as available. The dirty-checking
  `UPDATE … WHERE id = ?` waits for the other writer's lock, re-checks only `id = ?`, and
  overwrites. The rule was checked once, in Java, on data that was already out of date.

**Better (`LockingReservationService`): pessimistic lock with JPA.**

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select c from Copy c where c.id in :ids order by c.id")
List<Copy> lockCopies(@Param("ids") Collection<UUID> ids);
```

**Checklist:** each item can be shown in the code and asked in the defense.

1. Lock, check and write in **one transaction**.
2. **Check the copies that the locked query returned.** If the copies were already loaded without a
   lock in the same transaction, the locked query returns those same managed instances, with their
   old state.
3. **Throw, do not return.** Throwing rolls back a partial success. The three-copy scenario shows
   that the two free copies stay available.
4. **Fewer rows than requested is a rejection:** a copy that does not exist cannot be locked.
5. **Always lock in the same order:** `order by c.id` in the query. Sorting the list in Java does
   not change the order of the locks. Without a fixed order, two requests that ask for the same
   copies in opposite orders deadlock.
6. **Set a lock timeout** (`jakarta.persistence.lock.timeout` hint) and map it to a clean response.

**Points to explain:**

- **What the 49 losers do while the winner holds the lock:** they wait on the `SELECT … FOR
  UPDATE`. When the winner commits, they read the copy as reserved and are rejected.
- **Correctness costs time:** compare the latency of the two scenarios. The fix buys correctness,
  not speed.

### 4.5 Per-member limit (write skew)

**Scenario.** A member can have at most 5 active items (loans plus live reservations). The
concurrency scenario simulates two browser tabs of the same member. Each tab reserves 3 copies,
and the copies are different, so the limit must not end at 6. A member's **first** request must
be accepted.

**Naive (`NaiveReservationService` with the limit check).**

1. Count the member's active loans and reservations.
2. If `count + requested <= 5`, continue with the reservation.

**What the scenario shows, and why.**

- Both tabs read 0, both pass the check and both commit, so the member ends with 6 items.
- **Write skew, not a lost update.** Both transactions read the same *set* of rows but write
  *different* rows. Locking the copies, as in 4.4, does not help here: the copies are disjoint, so
  no transaction ever waits for the other.
- **Why the usual reflexes fail:**
  - `FOR UPDATE` on the member's loans locks nothing on the first request, and it cannot see rows
    that another transaction is inserting (**phantoms**).
  - `REPEATABLE READ` in PostgreSQL is snapshot isolation, and snapshot isolation *allows* write
    skew.
  - A unique constraint cannot check a sum.

**Better (`LockingReservationService`): turn the limit into a conflict on one row.**

1. Lock the **`Member`** row (`entityManager.find(Member.class, id, PESSIMISTIC_WRITE)`) before
   counting.
2. Count, check and reserve in the same transaction.

The second tab waits for the first tab to commit, then counts 3 and is rejected.

- **Lock order:** take the member lock before the copy locks, everywhere.
- **Rollback:** if the copies are then unavailable, the exception rolls back the whole request.

**Points to explain:**

- **The first-request trap.** Locking the member works because the member row always exists.
  Suppose the limit were per member *and per branch*, kept in a counter row of its own. Then the
  first request would have no row to lock, and "zero rows updated" would mean either "no row yet"
  or "limit exceeded". The usual fix creates the counter row and locks it in one statement. That
  is one of the few places where a native `INSERT … ON CONFLICT` query would be justified.
- **The cost:** all operations of the same member are serialized. Operations of different members
  are not affected.

## 5. Code organization

- **Layers, as in Affora:** controllers → application services, which return DTOs → rich domain
  entities with JPA annotations → Spring Data repositories.
- **One package per lesson.** Each package contains:
  - the naive and the better service, named after their mechanism (`NaiveCopyImportService` /
    `BatchedCopyImportService`), sharing the same domain and the same DTOs;
  - the `/naive/...` and `/better/...` endpoints;
  - the two scenarios.
- **The difference is in one place.** The naive and the better versions differ only where the
  lesson is, so the class can compare the two classes directly.
- **`scenario` package:**
  - the `Scenario` interface;
  - the `CommandLineRunner`;
  - `ScenarioController`;
  - the measurement helper (Hibernate `Statistics`, timer, heap);
  - the `RaceWindow` bean.
- **Repository root:**
  - `scenarios.http`;
  - `compose.yaml` (PostgreSQL);
  - `src/main/resources/data.sql`;
  - the CSV file for the import.
- **Tests (examples):** Testcontainers PostgreSQL, Given / When / Then style.
  - **Domain unit tests**, one per domain rule.
  - **Statement-count test per read case.** It asserts that the better version keeps a fixed
    number of statements.
  - **Concurrency tests:**
    - fifty threads for one copy: exactly one success;
    - three copies with one already taken: nothing left reserved;
    - two tabs against the member limit: one accepted, one rejected.
  - **Leak test** for the shelf map: the response never contains member data or a deadline.

## 6. Vocabulary

**Must know**

- **Persistence and JPA:**
  - persistence context / first-level cache;
  - managed, detached and transient entities;
  - dirty checking;
  - flush and flush mode `AUTO`;
  - `persist` vs `merge`.
- **Reading data:**
  - lazy vs eager loading;
  - N+1 problem;
  - `JOIN FETCH`;
  - DTO / constructor-expression projection;
  - read-only transaction;
  - `LazyInitializationException`.
- **Writing data:**
  - JDBC batching;
  - transaction boundary and rollback rule.
- **Concurrency:**
  - `READ COMMITTED`;
  - check-then-act / race condition;
  - lost update;
  - write skew;
  - pessimistic lock (`PESSIMISTIC_WRITE`, `SELECT … FOR UPDATE`);
  - deadlock and lock ordering.
- **Testing and measuring:**
  - `CountDownLatch` / `ExecutorService`;
  - Testcontainers;
  - query plan (`EXPLAIN ANALYZE`).

**Advisable**

- **Fetching:**
  - entity graph;
  - `default_batch_fetch_size`.
- **Writing data:**
  - `reWriteBatchedInserts`;
  - `GenerationType.IDENTITY` and batching.
- **Caching:** second-level cache.
- **Isolation:**
  - phantom read;
  - snapshot isolation / `REPEATABLE READ`;
  - `SERIALIZABLE`.
- **Locking:** lock timeout.
