# Library example: performance and contention with JPA

A small Spring Boot application, set in a public library, that demonstrates the concepts of
Deliverable 2: the N+1 problem, projections, bulk writes, lost updates and write skew. Every
concept is implemented twice, side by side: a **naive** version, which works for one user and a
little data, and a **better** version. Each version has a **scenario** that runs it, measures it
and prints a report, so the two can be compared in class.

The design and the concepts are explained in [`design/concepts.md`](design/concepts.md) and
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

## The demonstration, lesson by lesson

The numbers below come from one run on an Apple M3 Pro with 18 GB. Other machines give
other values, with the same ratios.

### 1. Catalogue: the N+1 problem

| Scenario | What it does | What to show |
| --- | --- | --- |
| `catalogue-naive` | Loads the books, then each book's author and subjects lazily | 624 statements for 321 books (1 + 302 authors + 321 subject lists) |
| `catalogue-join-fetch` | One `JOIN FETCH` query | 1 statement, but still 643 managed entities |
| `catalogue-projection` | One query into records | 1 statement, no entities |

The naive report also shows the 20 books of `Prolific Author`: 22 statements instead of 41,
because the persistence context answers the repeated author. Endpoints: `GET /naive/books`,
`GET /better/books/join-fetch`, `GET /better/books/projection`.

### 2. Shelf map: entities and response size

| Scenario | What it does | What to show |
| --- | --- | --- |
| `first-level-cache` | The same copy looked up twice, in one transaction and in two | 1 statement and the same object, then 2 statements and two objects |
| `shelf-map-naive` | Central's 20,000 copies as entities, one JSON object per copy | Only 2 statements, but 20,301 entities and 2.7 MB of JSON |
| `shelf-map-projection` | Scalars grouped by shelf, with parallel arrays | 1 entity (the branch) and 1.3 MB |

Both versions show the 100 expired reservations as available, and neither reveals who holds
a copy or until when. Endpoints: `GET /naive/branches/Central/shelf-map`,
`GET /better/branches/Central/shelf-map`.

### 3. Import: writing in bulk

| Scenario | What it does | What to show |
| --- | --- | --- |
| `import-naive` | Imports 5,000 rows into Annex, then the same file again: lookups and `save()` per row | First import: about 21 s; 15,001 statements; 5,000 JDBC batches of one insert each |
| `import-batched` | The same 5,000 rows, then 20,000: one lookup for all rows, `persist()`, flush + clear every 500 | About 0.3 s for 5,000 rows and 0.5 s for 20,000; 3 statements and 40 batches |

**The whole `import-naive` scenario (both imports) takes about 40 seconds**, longer on a slower
machine. Its request in `scenarios.http` raises the HTTP Client read timeout (`# @timeout 300`):
without it the client can give up with a `ReadTimeoutException` while the server is still
importing (`--scenario=import-naive` on the command line has no such limit). The cost grows with
the square of the rows, which is why it runs 5,000 rows and not 20,000 (that took about seven minutes). The `.http` file also
uploads `annex-5000-error-last-row.csv`, whose last row has an unknown ISBN. The import answers
`400` with the line number and imports nothing: the naive version writes 4,999 copies before it
finds the error, and the rollback undoes them.

### 4. Reservation: lost update

| Scenario | What it does | What to show |
| --- | --- | --- |
| `reservation-naive` | 50 members reserve the bestseller's single copy at the same instant; check, then write | 50 × `201`: fifty promises for one copy |
| `reservation-pessimistic` | The same, with `SELECT … FOR UPDATE` | 1 × `201`, 49 × `409` |
| `reservation-overlap-naive` | Two members reserve `[N0001, N0002]` and `[N0003, N0002]` at once | N0002 promised to both |
| `reservation-overlap-pessimistic` | The same, with locks | One wins; the loser's other copy stays available |

The concurrency scenarios hold every thread between the read and the write (a `RaceWindow`), so
the race happens on every run instead of only when the timing is unlucky. Endpoints:
`POST /naive/reservations`, `POST /better/reservations`.

### 5. Member limit: write skew

| Scenario | What it does | What to show |
| --- | --- | --- |
| `member-limit-naive` | A member with 2 loans reserves 2 different copies in two tabs at once; copies locked, count unlocked | 2 × `201`: 6 items for a limit of 5 |
| `member-limit-locked` | The same, locking the member row before counting | 1 × `201`, 1 × `409`: 4 items |

Endpoint: `POST /better/reservations/member-locked`.

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
├── domain        entities and their rules (Copy, Reservation, MemberLimit, ...)
├── persistence   Spring Data repositories and projection records
├── catalogue     lesson 1: naive, JOIN FETCH and projection services, controller, scenarios
├── shelfmap      lesson 2
├── importing     lesson 3
├── reservation   lessons 4 and 5
├── web           ProblemDetailsHandler (errors as application/problem+json)
└── scenario      Scenario, ScenarioRunner, ScenarioController, Measurement, RaceWindow, ...
```

Each lesson's package holds its naive and better services next to each other. They share the
domain and the DTOs, and differ only where the lesson is.
