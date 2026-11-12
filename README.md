# Library example: performance and contention with JPA

A small Spring Boot application, set in a public library, that demonstrates the concepts of
Deliverable 2: the N+1 problem, projections, bulk writes, lost updates and write skew. Every
concept is implemented twice, side by side: a **naive** version, which works for one user and a
little data, and a **better** version. Each version has a **scenario** that runs it, measures it
and prints a report, so the two can be compared in class.

The lessons for students are in [`lessons/`](lessons/) (start with
[`lessons/README.md`](lessons/README.md)). The documents used to design the example are in
[`design/`](design/), among them [`design/concepts.md`](design/concepts.md) and
[`design/domain-and-examples.md`](design/domain-and-examples.md).

## Requirements

- Java 25 (the Maven wrapper downloads Maven).
- Docker running. The application starts PostgreSQL 17 from `compose.yaml` by itself.
- To send the requests in `scenarios.http`: IntelliJ IDEA's HTTP client, or the VS Code
  REST Client extension.

The application listens on port **8080**. PostgreSQL is mapped to port **5433**, so it does not
clash with another PostgreSQL on the default port 5432.

## Start the application

```bash
./mvnw spring-boot:run
```

At every start, Hibernate recreates the schema and `src/main/resources/data.sql` loads the
example data, so every start begins from the same data:

| Branch | Content | Used by |
| --- | --- | --- |
| Central | 20,000 copies of 300 books, with loans and reservations (100 of them already expired) | Shelf map |
| North | The bestseller (`N-BEST`, a single copy) and 30 ordinary copies (`N0001`–`N0030`) | Reservation, member limit |
| Annex | Empty | Import |

There are also 321 books, 60 scenario members (`member01@library.test` … `member60@library.test`)
with no loans or reservations, and 500 readers who own Central's loans and reservations.

Ids are random UUIDs that change at every start. The endpoints therefore take business keys:
branch names, barcodes and member emails.

## Run the scenarios

### From `scenarios.http` (for the class)

With the application running, open [`scenarios.http`](scenarios.http) and send the requests in
order. Each `POST /scenarios/{name}` runs one scenario and returns its report as JSON, while the
application's console shows the SQL. `GET /scenarios` lists them all. The file also contains
requests to the use-case endpoints themselves, so the class can see the real responses.

A scenario first puts its own data back to a known state. Scenarios can therefore be run in any
order, any number of times.

### From the command line

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--scenario=catalogue-naive,catalogue-join-fetch
```

This runs the named scenarios, prints their reports in the console and stops the application.

## The lessons

The explanations for students are in [`lessons/`](lessons/). Start with
[`lessons/README.md`](lessons/README.md): it describes each document and gives a recommended reading
order. Each lesson shows a naive version, a scenario that measures it, why it fails, and the better
version.

**[`lessons/domain-reads-and-reservations.md`](lessons/domain-reads-and-reservations.md)**: the
domain, the data loaded at startup and how the scenarios work, then:

- **The catalogue: the N+1 problem when reading.** Scenarios `catalogue-naive`,
  `catalogue-join-fetch`, `catalogue-projection`.
- **The shelf map: the cost of entities when there is no N+1.** Scenarios `first-level-cache`,
  `shelf-map-naive`, `shelf-map-projection`.
- **Reserving the last copy: check-then-act and the lost update.** Scenarios `reservation-naive`,
  `reservation-pessimistic`, `reservation-overlap-naive`, `reservation-overlap-pessimistic`.
- **The per-member limit: write skew.** Scenarios `member-limit-naive`, `member-limit-locked`.

**[`lessons/bulk-import.md`](lessons/bulk-import.md)**: importing a branch's copies from a CSV
(write-side N+1, `persist` vs `merge`, auto-flush, dirty checking, JDBC batching). Scenarios
`import-naive`, `import-batched`.

**[`lessons/reservation-expiry-lessons.md`](lessons/reservation-expiry-lessons.md)**: reservation
expiry, collection and return. Its scenarios are the "Expiry" requests of `scenarios.http`.

- Lesson 0: Sequential tests cannot see a race
- Lesson 1: Lost update between two transitions
- Lesson 2: Read the time after you have the right to decide
- Lesson 3: An affected-row count you ignore is not a guarantee
- Lesson 4: A lock does not refresh an entity you already loaded
- Lesson 5: Lazy expiry: decide on read, write only when you must
- Lesson 6: When lazy expiry and the sweep meet: release only what you still own
- Lesson 7: Lock order, deadlocks, and a job that never waits
- Lesson 8: Two application instances, and effects exactly once
- Lesson 9: Side effects that leave the system: send them after the commit
- Lesson 10: Designing the sweep: one big transaction or many small ones
- Lesson 11: A retried return, idempotency, and full-row updates

**[`lessons/conditional-update-vs-select-lock.md`](lessons/conditional-update-vs-select-lock.md)**:
the two correct ways to let only one transaction perform a transition (`SELECT … FOR UPDATE` vs a
conditional `UPDATE`), on the return of a loan.

**[`lessons/collect-expiry-lock-vs-conditional.md`](lessons/collect-expiry-lock-vs-conditional.md)**:
the same comparison on the project's collect and expire code. Scenarios `collect-expiry-locked`,
`collect-expiry-conditional`.

**Running `import-naive` in class:** the whole scenario (both imports) takes **about 40 seconds**,
longer on a slower machine. Its request in `scenarios.http` raises the HTTP Client read timeout
(`# @timeout 300`). Without it, the client can give up with a `ReadTimeoutException` while the
server is still importing. `--scenario=import-naive` on the command line has no such limit.

## Looking inside the database

```bash
docker compose exec postgres psql -U library -d library
```

From there you can run `EXPLAIN (ANALYZE, BUFFERS)` on a statement copied from the SQL log, or
watch the locks while a scenario runs.

## Tests

```bash
./mvnw verify
```

The tests run against PostgreSQL in Testcontainers (Docker must be running). They are examples
of how each concept is tested: domain rules, statement counts, concurrency with real
transactions, privacy of responses, and the endpoints. They are not the demonstration.

## Troubleshooting

- **Port 8080 or 5433 is in use:** stop the other application (for example another Spring Boot
  project on 8080), or change `server.port` in `application.properties` (and `@host` in
  `scenarios.http`) or the port mapping in `compose.yaml`.
- **The application cannot start PostgreSQL:** check that Docker is running. `docker compose ps`
  shows the container.
- **A concurrency scenario logs "Race window broken":** some thread never reached the barrier,
  usually because the connection pool is too small. The pool is set to 60 connections, enough
  for the fifty simultaneous reservations.
- **Times differ from the README:** expected. Compare the versions with each other on the same
  machine, not with these numbers. Statement and entity counts should be identical.

## Code layout

```text
src/main/java/cat/tecnocampus/library
├── (root)        LibraryApplication, ClockConfiguration, MovableClock (the clock scenarios can stop and move)
├── domain        entities and their rules (Copy, Reservation, MemberLimit, ...)
├── persistence   Spring Data repositories and projection records
├── catalogue     catalogue lesson: naive, JOIN FETCH and projection services, controller, scenarios
├── shelfmap      shelf map lesson
├── importing     bulk import lesson
├── reservation   reserving the last copy and the member limit
├── expiry        reservation expiry lessons: collect, cancel and expire (naive, locking, conditional),
│                 the sweep and its scheduler, expiry notices, scenarios
├── loan          return of a loan (naive and conditional), the retried-return lesson
├── web           ProblemDetailsHandler (errors as application/problem+json)
└── scenario      Scenario, ScenarioRunner, ScenarioController, Measurement, RaceWindow, ...
```

Each lesson's package holds its naive and better services next to each other. They share the
domain and the DTOs, and differ only where the lesson is.
