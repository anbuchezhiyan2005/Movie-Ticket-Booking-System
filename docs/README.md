# Movie Ticket Booking System

A single-store movie ticket booking platform: admins own theatres, screens,
and shows; customers discover shows, hold seats via an OTP-first booking
flow, pay from a wallet, cancel with a refund governed by business rules, and
are let in through a QR gate scanned at the counter.

This is the single source of truth for how the system works **right now**.
Update it when behavior, endpoints, persistence, or concurrency rules change.

## 1. System purpose

Two roles:

| Role | Can do |
|---|---|
| ADMIN | owns theatres, manages screens and shows |
| CUSTOMER | registers/logs in, browses movies, finds shows, views seats, holds and books seats, pays from a wallet, views/cancels bookings, receives refunds, logs out |

Business relationship:

```text
Admin -> Theatre -> Screen -> Show -> Movie
                                -> Show seats -> Booking -> Customer
```

Movies are a **global catalogue** — fixed seed data. An admin does not own a
movie; an admin owns theatres, and a show connects a movie to a screen in one
of those theatres.

## 2. Tech stack

Java 17 · Maven WAR (`movie-booking`) · Apache Tomcat + Jersey (REST,
`@Path("/api/*")`) · MySQL (`movie_booking`, HikariCP + JDBC repositories) ·
ThreadLocal transactions · static HTML/JS/CSS in the same WAR · jBCrypt
passwords · scheduled executors (expiry/cache) · angus-mail + zxing (OTP
emails, confirmations, QR tickets) · nimbus-jose-jwt (Google OAuth).

## 3. Repo map

```
backend/src/main/java/
  config   bootstrap, DB (AppLifecycleListener, JerseyApplication, Database)
  filter   AuthFilter, session/CSRF enforcement
  resource REST entry points          service  business rules + transactions
  repository SQL                      dto/     {request,response}
  model, enums, util, cache, exception
backend/src/main/resources/db/    schema.sql + seed.sql (schema truth)
backend/src/main/webapp/          static frontend (app.js, index.html, styles.css)
backend/src/test/                 JUnit 5 unit + integration + HTTP smoke tests
docs/                             this file + OAUTH_SETUP.md
```

Files by responsibility:

| Responsibility | File/dir |
|---|---|
| Build/deploy wiring | `backend/pom.xml`, `backend/src/main/webapp/WEB-INF/web.xml` |
| App wiring, startup/shutdown | `config/JerseyApplication.java`, `config/AppLifecycleListener.java`, `config/RuntimeLifeCycle.java` |
| Auth | `resource/AuthResource.java`, `service/AuthService.java`, `filter/AuthFilter.java`, `util/RequestUsers.java`, `service/OAuthStateService.java` |
| Frontend | `backend/src/main/webapp/{index.html,app.js,styles.css}` |
| Movie catalogue | `resource/MovieResource.java`, `service/MovieService.java`, `repository/MovieRepository.java` |
| Shows/seats | `resource/ShowResource.java`, `service/ShowService.java`, `cache/SeatAvailabilityCache.java` |
| Booking/cancellation | `resource/OtpResource.java`, `resource/BookingResource.java`, `service/BookingService.java`, `repository/ShowSeatRepository.java`, `repository/BookingRepository.java` |
| OTP | `service/OtpService.java`, `service/OtpEmailService.java`, `repository/OtpChallengeRepository.java` |
| Payment | `service/PaymentService.java`, `repository/UserRepository.java` |
| Gate entry | `resource/GateResource.java`, `service/GateValidationService.java`, `service/GateTokenService.java` |
| Database | `config/Database.java`, `src/main/resources/db/{schema,seed}.sql` |

## 4. Run it

1. Stand up MySQL, apply `db/schema.sql` then `db/seed.sql`.
2. Provide your own credentials in `backend/src/main/resources/db.properties`
   (`db.url`, `db.user`, `db.password`) or the `DB_URL`/`DB_USER`/`DB_PASSWORD`
   env vars (env wins; a local `.env` also works). Never commit secrets or
   `.env`.
3. Mail/scanner/OTP/QR settings come from env (`MAIL_*`, `SCANNER_*`,
   `OTP_*`, `QR_HMAC_SECRET`); defaults exist only where safe. OAuth setup →
   `docs/OAUTH_SETUP.md`.
4. `mvn -q -f backend/pom.xml package` → deploy `backend/target/movie-booking.war`.
5. Open `https://<host>:8443/movie-booking/` (TLS is enforced, see §6).
   All API routes live under `<context>/api`.

## 5. AuthN/AuthZ

- **Sessions**: login/register set an `HttpOnly, Secure, SameSite=Lax`
  cookie (30-min timeout, cookie-only tracking). The server keeps `userId` +
  role in the session; `AuthFilter` guards every URL and allowlists only
  public reads (health, movies, shows, seats), OAuth start/callbacks, auth,
  and static assets. Missing/invalid identity → 401.
- **CSRF**: `POST/PUT/PATCH/DELETE` must send `X-CSRF-Token` matching the
  session token; mismatch or absence → 403. `AuthFilter` places validated
  identity attributes on the request for resources.
- **Roles**: enforced via `RequestUsers.requireCustomer/requireAdmin`.
- **Registration/login specifics**: customer `POST /api/auth/register`
  forces role `CUSTOMER`, normalizes email, rejects duplicates, BCrypt-hashes
  the password, and starts the wallet at **10000 cents** (admins start at 0).
  `POST /api/auth/register-admin` additionally requires an access code
  matching the `movie.booking.admin.secret` JVM property (default
  `REELRESERVE-ADMIN-KEY`). Login returns the profile, wallet balance, and
  CSRF token for the session.
- **OAuth**: Google + Twitter sign-in and account linking, server-side PKCE +
  `state` + `nonce`, flows keyed to `docs/OAUTH_SETUP.md`. OAuth state
  transactions live 5 minutes.
- **Scanner**: the gate does **not** use sessions. `POST
  /scanner/tickets/redeem` authenticates with `X-Scanner-API-Key`.

## 6. HTTP behavior

`AuthFilter` sets `Content-Security-Policy`/`X-Frame-Options`, enforces
`transport-guarantee=CONFIDENTIAL`, and logs a per-request line with
`X-Request-Id`. Typed exceptions map to JSON `{"error": ...}`; unexpected
errors → 500 with no internals leaked.

| Status | Meaning |
|---|---|
| 400 | validation / business rule failure |
| 401 | unauthenticated |
| 403 | forbidden / role / ownership failure |
| 404 | resource not found |
| 409 | state conflict — commonly a seat already claimed |
| 500 | unexpected server/DB failure |

Expected seat contention is a normal retryable outcome; a database deadlock
is a transient infrastructure failure and needs separate retry/order
handling. Do not conflate the two (see §8).

## 7. Domains and state

Seat layout is **derived**, not stored per-seat. A screen stores a compact
`row_range`/`seats_per_row`; the app generates the grid. A `show_seat` row
exists only when a seat is claimed. Seat status derives from it:

```text
no show_seat row                          -> AVAILABLE
show_seat + AWAITING_OTP/PENDING booking  -> HELD
show_seat + CONFIRMED booking             -> BOOKED
cancellation or expiry deletes the row    -> AVAILABLE again
```

DB state lifecycle:

| Entity | Lifecycle |
|---|---|
| User | register → row; login → session (row read); logout → session invalidated, row remains |
| Booking | `AWAITING_OTP → CONFIRMED → CANCELLED`; `AWAITING_OTP/PENDING → EXPIRED` |
| OTP challenge | created (active) → verified (consumed); resend supersedes the old; expired booking invalidates active challenges |
| Gate token | booking confirmed → `ISSUED`; scanner redemption → `USED`; cancelled/expired/ended → rejected by validation |

## 8. Core flows

### Booking (OTP-first — the only live customer path)

1. `POST /api/bookings/otp/request` `{showId, seats:[{rowLabel, seatNumber}]}`
   → `BookingService.holdTickets()` validates the customer, loads the future
   show + screen/movie/theatre/admin, validates row/seat ranges and
   duplicates, computes `ticket_price × seats`, creates a booking
   `AWAITING_OTP` with a **5-minute expiry**, inserts `show_seat` rows, marks
   the cache `HELD`. The resource then issues an OTP challenge and either
   emails the 6-digit code (`OTP_DELIVERY_MODE=SMTP`) or returns it inline as
   `simulationOtp` (`OTP_DELIVERY_MODE=SIMULATION`). SMTP failure releases
   the hold and fails the request.
2. `POST /api/bookings/{id}/otp/verify` `{challengeToken, code}` →
   `confirmHeldBookingWithOtp()` verifies the code **inside the same
   transaction** as confirmation/payment: lock booking row → validate
   ownership/status/hold expiry → load claimed seats → debit customer wallet
   → credit theatre admin wallet → `CONFIRMED`, clear expiry → mark seats
   `BOOKED` → issue gate token → commit. After commit a confirmation email is
   queued asynchronously; delivery failure is logged, never rolled back.
3. Resend (`POST .../otp/resend`) and the background expiry release abandoned
   holds.

OTP challenge details: the DB stores **hashes** of the token and code; the
challenge carries a purpose (booking vs cancellation), 5-minute lifetime,
and a 60-second resend cooldown; verification checks user, booking, purpose,
expiry, attempt count, and code, then consumes the challenge.

### Cancellation

OTP-gated, mirroring the booking flow
(`POST /api/bookings/{id}/cancel/otp/request` → `.../verify` → 204). The
verify transaction: lock row → validate ownership/status → reject after show
start → compute refund → debit admin wallet → credit customer wallet → delete
`show_seat` rows → `CANCELLED` → remove seats from cache → commit. The gate
token is left in place but validation rejects redemption for a cancelled
booking.

Refund rule (single business function evaluated at cancellation time):

```text
more than 30 minutes before show start  -> 100% refund
within 30 minutes                       -> 75% refund (customer keeps 25%)
after show start                        -> cancellation rejected
```

### Gate entry

The confirmed booking carries a QR (emailed) whose token is issued with a
signature valid until show end. The scanner posts the signed token to
`POST /api/scanner/tickets/redeem` with `X-Scanner-API-Key`; the gate service
verifies the QR signature, hashes the token, **atomically consumes it**, and
rejects reused, expired, cancelled, ended, or invalid tickets. The gate token
is separate from the OTP — OTP confirms a booking/cancellation, the token
proves entry.

### Not implemented by design

`POST /api/bookings` and `POST /api/bookings/{id}/cancel` exist as routes but
always reject — booking requires email (OTP) verification, so they must never
be wired to direct booking without an OTP step.

## 9. Concurrency and seat safety

- **DB is the final arbiter**: a unique constraint on
  `(show_id, row_label, seat_number)` makes simultaneous claims atomic; the
  loser gets 409. Multi-seat requests are all-or-nothing — any claimed seat
  failure rolls back the whole request.
- **App locks**: `BookingService` short-locks active seats in an in-memory
  `ConcurrentHashMap` keyed `(showId, row, seat)`; same-JVM only, not a
  distributed lock, conflicts surface before the DB transaction starts.
- **Seat order**: seat claims are normalized to a consistent order to reduce
  circular waits; MySQL deadlocks can still occur under high contention.
- **Transactions**: everything commits or rolls back together inside one
  `Database.inTransaction(...)`; wallet debit/credit is atomic (insufficient
  balance rolls back the confirm); deadlocked transactions retry with capped
  exponential backoff.
- **Cache**: `SeatAvailabilityCache` derives occupancy per show, surgically
  mutates individual seats on hold/confirm/cancel, and rebuilds on miss from
  `show_seat` + booking status. It is a local performance layer — MySQL is the
  source of truth.

## 10. Cache and background processing

- **SeatAvailabilityCache**: per-show entries, concurrent maps, surgically
  added/changed/removed seats, rebuild-on-miss from DB. Never authoritative.
- **BookingExpiryScheduler**: every 30 seconds, calls
  `expirePendingBookings()` — find expired `PENDING`/`AWAITING_OTP` bookings,
  delete their claimed seats, invalidate their OTP challenges, `EXPIRED`,
  drop from cache. This is how abandoned holds release seats.
- **Confirmation email executor**: single daemon thread; a failed async email
  never rolls back a committed booking.

## 11. Admin workflow

Admins register via the protected registration route (admin secret) and log
in like customers. Admin mutation surface:

```text
POST/PUT/DELETE /api/theatres/{id}
GET/POST/PUT/DELETE /api/screens/{theatreId | id}
POST/PUT/DELETE /api/shows/{id}
```

Ownership is enforced through the theatre → screen → show chain: an admin
cannot mutate another admin's hierarchy. Show timing/overlap/dependency rules
protect existing booking data. Movies are seed data — no movie mutation API.

## 12. Money

- Amounts are **integer cents** end to end; totals use `Math.multiplyExact`.
- Payment = wallet debit from the customer, credit to the show's admin.
- Refund = the §8 rule applied at cancellation time. No discount/surcharge
  logic exists.

## 13. Testing

`mvn -q -f backend/pom.xml test` — unit tests (services, OAuth state), MySQL
integration tests (repositories and booking/cancellation workflows,
including 75% refund math), a route test, and an HTTP smoke test.

## 14. Known gaps and design risks

1. **In-memory seat locks are not distributed** — multiple Tomcat instances
   rely on the DB uniqueness constraint, not the `ConcurrentHashMap`.
2. **DB deadlocks can still occur** under high contention; consistent seat
   order reduces but doesn't eliminate them.
3. **Holds and the OTP window are coupled** — abandoned `AWAITING_OTP`
   bookings depend on the 30-second expiry scheduler to release seats.
4. **The cache is local and derived** — cache bugs must not be treated as
   durable booking truth.
5. **Confirmation email delivery is async** — a successful booking does not
   guarantee email delivery.
6. **Gate token lifecycle needs review** — cancellation is rejected at
   validation time even if the token row stays issued.
7. **HTTPS in local dev** needs Java/browser truststore configuration.

## 15. First-principles mental model

Debug via five invariants — identify which one is violated first, and it
names the owning layer:

| Invariant | Violation → look at |
|---|---|
| **Identity** — every protected action resolves to one authenticated user+role | filter/session/auth |
| **Ownership** — customer mutates only own bookings; admin only own theatre chain | `RequestUsers`/service authorization |
| **Seat uniqueness** — one `show_seat` row max per `(show_id, row_label, seat_number)` | BookingService/repository/schema |
| **Transaction** — booking state, seat claims, wallet moves commit/roll back together | Database transaction/service/payment |
| **Lifecycle** — every seat claim ends `HELD await → BOOKED → AVAILABLE` | expiry scheduler/cache/cancellation |

## 16. Deliberate simplifications (locked)

No genres, no special pricing, no per-seat status column (derived instead),
no `booking_seat` join table — sparse `show_seat` only, uniform seats per
screen, OTP-first booking as the only customer route, and the `POST
/bookings`/`{id}/cancel` stubs kept as documented dead ends. See the domain
constraints in `db/schema.sql` and the service layers for the full ruleset.