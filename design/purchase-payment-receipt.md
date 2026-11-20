# Selling books: idempotent purchase, external payment, receipt — naive and better

This is the plan for the library version of Affora's Deliverable 4 use cases **UC-18** (idempotent
purchase), **UC-19** (charging an external payment provider) and **UC-20** (confirmation and
issuance), as simplified in the current statement of Affora's Deliverable 4:
a decline or a gateway failure fails the purchase, there is no retry, no indeterminate outcome and
no reconciliation, and the main lesson of UC-19 is *never call an external system from inside a
database transaction*. It follows the same rules as the other lessons ([concepts.md](concepts.md),
[domain-and-examples.md](domain-and-examples.md), [reservation-expiry-and-return.md](reservation-expiry-and-return.md)):
each concept gets a **naive** version and a **better** version side by side in the code, and each
version has a **scenario** that prints the same facts.

Until now the library only lends books. It now also **sells** them: a small shop catalogue lists the
books for sale, each with a price and a stock. A member buys a list of books in one request; the
application checks which ones are still in stock, sells those, and skips the others.

---

## 1. Mapping from Affora to the library

| Affora (D4) | Library | Notes |
| --- | --- | --- |
| Seats of a performance, `FREE` / `HELD` / `SOLD` | `BookOffer`: a book for sale, with a price and a **stock** counter | No numbered items: closer to Affora's general admission (UC-16) |
| Hold (UC-14) + hold confirmation (UC-17) before paying | None. The purchase takes the stock itself, in its first transaction | The library has no "hold then pay" step: one request does it all |
| `POST /orders` + `Idempotency-Key`, body `{holdId, paymentToken}` | `POST /naive/purchases` and `POST /better/purchases` + `Idempotency-Key`, body `{memberEmail, isbns, paymentToken}` | Members are named by email, as in the other library endpoints (no authentication) |
| All the seats of the hold or nothing | **Partial**: the books in stock are sold, the others are listed as skipped | Requirement of this example. Nothing in stock → `409`, nothing created |
| Price breakdown frozen into the order (UC-08) | Each line freezes the offer's price at purchase time | A later price change does not alter a past purchase |
| `Order` with explicit intermediate states | `Purchase` with `PAYMENT_PENDING`, `PAID`, `DECLINED`, `PAYMENT_FAILED` | No `RECONCILE`: Affora's Deliverable 4 has no indeterminate outcome |
| Fake payment gateway, `fake-payment-gateway/` | A copy of it in this repository, `fake-payment-gateway/`, started by `compose.yaml` on host port **9091** | Unchanged code (`tok_slow` and `tok_pending` still exist; the shop only relies on `tok_ok`, `tok_reject`, `tok_fail`, and `tok_slow` to make it slow); webhooks stay off (no callback URL). 9091, so it can run next to Affora's gateway on 9090 |
| JDK `HttpClient` adapter (`HttpPaymentGateway`) | Spring's **`RestClient`** (`RestClientPaymentGateway`) | Built from the Boot-configured `RestClient.Builder`, a connect timeout and no read timeout |
| Tickets issued with a signed code (UC-20/21) | A **receipt** for the buyer | No signature (UC-21 is out of scope) |
| Confirmation e-mail through an outbox (UC-20) | Receipt message through an outbox, "sent" by `ReceiptMailbox` (console + counter) | Same pattern as `ExpiryNotices` |

---

## 2. Requirements, adapted

### UC-18 · Idempotent purchase

- `Idempotency-Key` header mandatory (`400` without it).
- Body: the member's email, a non-empty list of ISBNs (no duplicates), and the payment token.
- Every ISBN must be in the shop catalogue (`404` otherwise). Books with no stock left are
  **skipped**: the purchase carries on with the others. If none is in stock: `409 nothing-available`,
  nothing is created and the key is not used up.
- Success: `201`, `Location: /purchases/{id}`, a body with the purchase id, the lines bought (ISBN,
  title, price), the skipped ISBNs, the total and the receipt.
- A retry with the same key **and the same body** returns the original answer, with the original
  status code. The same key with a **different** body is `422`.
- Ten simultaneous submissions with one key: **one purchase, one charge, ten identical answers**.
- Decisions to write down (Affora asks the students for both): what happens when the retry arrives
  while the first request is still running (**it waits for the answer, up to 15 s, then `503` "busy,
  retry with the same key"**), and how long a key is remembered (**as long as the purchase: keys are
  never deleted in this example; a real shop would delete them after, say, 24 h**).

### UC-19 · Charging the external provider

- The provider succeeds (`201`), declines (`402`) or fails (`503`).
- On a decline or a failure the purchase **fails** (`DECLINED` / `PAYMENT_FAILED`): the stock taken
  by the purchase goes back, **once**, and the member gets an error. The charge is **not retried**.
  Anything else the shop cannot use (a provider that cannot be reached, a `201` that does not match
  the charge, a `202`) is treated as a failure too.
- **Main lesson: never call an external system from inside a database transaction.** No database
  connection and no row lock are held while waiting for the provider.
- A connect timeout (nothing was sent: a clean failure) and **no read timeout**: a read timeout would
  give up on a request the provider may be charging, which is the indeterminate case Deliverable 4
  leaves out.
- Decision to write down (Deliverable 4 asks for it): what runs in which transaction, and what
  happens to the purchase and its stock if the process dies while waiting for the gateway (§4).

### UC-20 · Receipt and confirmation

- Once the charge is accepted, the purchase becomes `PAID`, a **receipt** is issued (number, lines,
  total, provider reference) and a **confirmation message** is sent to the member.
- If anything fails after the charge, no receipt is issued and no message is sent; the purchase is
  left `PAYMENT_PENDING` with its stock (the money may have moved), and a retry with the same key
  settles it with the provider's stored answer.
- A message never announces a receipt that does not exist.
- If the application dies right after the commit, the message is still sent once it comes back.

---

## 3. Domain

| Class | What it holds | Rules in the class |
| --- | --- | --- |
| `BookOffer` (new entity) | book (one offer per book), `priceCents`, `stock` | Stock never negative: enforced by the conditional `UPDATE` that takes one (`BookOfferRepository.takeOne`) |
| `Purchase` (new aggregate) | member, idempotency key, request fingerprint, lines (frozen ISBN, title, price), skipped ISBNs, total, status, provider reference, payment lease (nonce + until), number of attempts | `claimPayment(now)`, `settle(nonce, evidence)` → stock action, `abandonAttempt(nonce)`; the naive version uses `markPaid` / `markDeclined` / `markFailed` |
| `PurchaseStatus` | `PAYMENT_PENDING`, `PAID`, `DECLINED`, `PAYMENT_FAILED` | `isSettled()` for the final three |
| `PurchaseKey` (new entity, better version only) | member id + key (**unique constraint**), fingerprint, purchase id | The constraint is the guarantee; the naive version has no such row and can create duplicates |
| `Receipt` (new entity) | purchase (**unique**), number, member email, lines, total, provider reference, issued at | `Receipt.issueFor(purchase, now)` refuses unless the purchase is `PAID` |
| `ReceiptMessage` (new entity, outbox) | receipt id, member email, text, `PENDING` / `SENT`, sent at | `markSent(now)` |

The settle rule, in the entity (only the attempt holding the current lease may apply it):

| Evidence | Purchase | Stock |
| --- | --- | --- |
| `201` matching amount, currency, reference, with a provider reference | `PAID` | kept (sold) + receipt |
| `402` | `DECLINED` | **released** |
| `503`, unreachable, anything else | `PAYMENT_FAILED` | **released** |

---

## 4. The two versions

### Naive (`NaivePurchaseService`, one `@Transactional` method)

What an assistant writes when asked "a purchase that charges the card and sends a receipt":

1. look for a purchase with that key; if there is one, answer it (**check-then-act**, no constraint);
2. take the stock, create the purchase;
3. call the gateway **inside the transaction**;
4. `201` → `PAID`, issue the receipt and **send the message right away**; `402` → `DECLINED`,
   anything else → `PAYMENT_FAILED`, give the stock back (the same rule as the better version: not
   a defect);
5. commit.

### Better (`PurchaseService`, no transaction, coordinates four short steps)

| Step | Class | Transaction | What it does |
| --- | --- | --- | --- |
| Prepare | `PurchasePreparation.prepare` | short | insert the `PurchaseKey` first (the unique index decides), take the stock offer by offer in id order, create the purchase `PAYMENT_PENDING`. A collision rolls back and re-reads the winner's purchase in a fresh transaction (fingerprint checked: `422`) |
| Claim | `PaymentClaims.claim` | short, row lock | settled → replay; another attempt holds a live lease → wait 50 ms and claim again (max 15 s, then `503` busy); otherwise take a 15 s lease with a fresh nonce |
| Charge | `PaymentCharges.charge` | **none** (`Propagation.NEVER`) | one `RestClient` call, connect timeout 1 s, no read timeout, provider key = purchase id, no retry |
| Finish | `PaymentCompletion.finish` | short, row lock | fence on the nonce, `Purchase.settle`, apply the stock action, on `PAID` issue the receipt and insert the outbox message in the **same** transaction |
| Send | `ReceiptDispatcher.deliverAfterCommit` | own (`REQUIRES_NEW`), after `finish` returned | lock the message row (`SKIP LOCKED`), send, mark sent |
| (on failure) | `PaymentCompletion.abandon` | short | the finish threw after the provider answered: give up the lease; the purchase stays `PAYMENT_PENDING` with its stock (`202`), so a retry can charge again at once with the same provider key |

The message leaves through the outbox: the coordinator sends it as soon as `finish` has committed
and returned its connection (not from an `AFTER_COMMIT` listener: see §8), and a scheduled job
(`ReceiptDispatchScheduler`, every 2 s, `library.receipts.dispatch-enabled`) delivers whatever is
left (a crash after the commit). Each delivery locks its message row with `SKIP LOCKED`, so the
immediate send and the job never send the same message together. A crash between "sent" and
"marked sent" sends it again: **at least once**, which is the honest guarantee when the
effect leaves the system.

**If the process dies while waiting for the gateway** (the question Deliverable 4 asks):

- Naive: the transaction never commits. No purchase, the stock is back, the key is not saved; the
  card may have been charged, and a retry creates a new purchase (new provider key) and charges again.
- Better: the purchase was committed `PAYMENT_PENDING`, stock taken, with a 15 s lease. A retry with
  the same key waits while the lease lives, then takes over with a new nonce and charges with the
  same provider key: the provider replays its stored answer if the first call had charged. A late
  answer from the dead attempt is fenced off by the nonce. Without any retry the purchase stays
  `PAYMENT_PENDING` with its stock: there is no clean-up job (accepted gap of the simplification;
  see the lesson's "What this example leaves out").

---

## 5. Scenarios

All scenarios reset the shop (stock back to its initial values, purchases, receipts and messages
deleted, mailbox cleared) and the gateway (`POST /admin/reset`) first. The gateway's admin API is
called by the scenarios only, never by the purchase code.

| Lesson | Naive scenario | Better scenario | Facts printed |
| --- | --- | --- | --- |
| 1. One key, ten requests | `purchase-key-naive` | `purchase-key-better` | answers by status, purchases created, charges at the gateway, identical bodies, stock taken |
| 2. The slow provider and the pool | `purchase-pool-naive` | `purchase-pool-better` | 80 purchases with a 1.5 s provider; pool active/waiting while they wait; an unrelated catalogue read, as a client that gives up after 500 ms sees it |
| 3. A failure after the charge | `receipt-failure-naive` | `receipt-failure-better` | messages sent, receipts, purchase status, charges |
| 4. A crash after the commit | — | `receipt-restart-better` | messages before and after the "restart", outbox state |
| Outcomes (no naive) | — | `purchase-outcomes` | one purchase per token (`tok_ok`, `tok_reject`, `tok_fail`) and one partial purchase: status, answer, stock, receipt |

The pool scenarios keep Hikari's default checkout timeout (30 s): the naive version's damage shows
as a full pool, queued requests and a catalogue read that cannot answer within a client's 500 ms,
not as errors. See §8 for why a shorter timeout was tried and dropped.

---

## 6. Data

`data.sql` adds the shop: books 1–40 for sale at 9.00 € + 0.50 € × n, 100 copies each; *The Last
Copy* (`978-84-999999`) with 1 copy; book 41 (`978-84-000041`) sold out (0 copies).

---

## 7. Stages

1. Gateway copied, compose service, `RestClient` adapter and its test against the real container.
2. Domain (`BookOffer`, `Purchase`, `PurchaseKey`, `Receipt`, `ReceiptMessage`) and unit tests.
3. Naive purchase, endpoint, naive scenarios; tests asserting the naive failures.
4. Better purchase, outbox, endpoints, better scenarios; integration and end-to-end tests.
5. Measured runs, the lesson for students (`lessons/selling-books-lessons.md`), README and
   `scenarios.http`.

Measured results are added to §8 once they exist.

---

## 8. Measured results

Apple M3 Pro, 18 GiB, macOS 26, JDK 25; PostgreSQL 17 and the gateway in Docker Desktop. One run of
each scenario over HTTP on a running application, after a warm-up; reports in
[`evidence/selling-books-scenarios.json`](evidence/selling-books-scenarios.json). The pool pair was
run three more times with identical counts. The student-facing explanation, with the same numbers,
is [`../lessons/selling-books-lessons.md`](../lessons/selling-books-lessons.md).

| Scenario pair | Naive | Better |
| --- | --- | --- |
| 1. Ten requests, one key | 10 purchases, 10 charges, 10 receipts, 10 messages, 10 distinct bodies | 1 purchase, 1 charge, 1 receipt, 1 message, 10 identical `201` |
| 2. 80 purchases, provider 1.5 s, pool 60 | pool 60 active / 20 waiting; catalogue no answer in 500 ms (answered after 1.26–1.28 s); purchases median 3.07–3.09 s | pool 0 active; catalogue 6–10 ms; purchases median 1.62–1.65 s |
| 3. Failure after the receipt | no purchase, no receipt, **1 message**, 1 charge; retry: **2 charges, 2 messages** | `202 PAYMENT_PENDING`, 0 receipts, 0 messages, 1 charge; retry `201`, 1 / 1 / 1 / 1 |
| 4. Crash after the commit | — | 1 pending, 0 received; job sends 1, then 0; 1 received |
| Outcomes | — | `201` partial (2 bought, 1 skipped), `402`, `503` (one call, nothing recorded), `409` |

After the simplification to the current Deliverable 4, `purchase-outcomes` and `receipt-failure-better` were
measured again (2026-11-20, on another, slower machine; they report counts only, which do not depend on
the machine) and their entries in the evidence file replaced; the late-answer pair was removed. The
other entries are the original M3 Pro runs: the simplification did not change those code paths.

Findings while building it (they changed the design of §4):

- **Hikari ignores a runtime change of `connectionTimeout` until its housekeeper runs** (every 30 s):
  the pool copies the value from its configuration only then. The first pool scenario changed it
  through `HikariConfigMXBean` and got inconsistent results (the probe timed out, the queued
  purchases did not).
- **A global 500 ms `connection-timeout`** made the naive pool scenario fail loudly (20 × "no
  connection", catalogue failed after 507 ms) but broke `ReservationTest`'s fifty simultaneous
  reservations right after a context start, while Hikari was still opening its 60 connections. It
  was reverted; the scenario now measures the catalogue as a client with a 500 ms patience.
- **Sending the receipt from an `AFTER_COMMIT` listener with `REQUIRES_NEW`** holds two connections
  per request: the committed transaction's connection is still bound during after-commit callbacks.
  With 80 purchases finishing together, two runs out of three ended with 11–20 purchases whose
  payment could not be recorded (then `RECONCILE`) and Hikari timeouts with 73–77 waiting threads (under the 500 ms timeout of that
  moment). The coordinator now sends after `finish` has returned.
- **The JDK client reports a refused connection as `ConnectException` wrapping
  `ClosedChannelException`**: the adapter walks the cause chain instead of looking only at the
  innermost cause.
- **Spring Boot's Docker Compose support skips `docker compose up` when any service of the file is
  already running**: with PostgreSQL left running from an earlier session, the gateway was not
  started. Documented in the README.
