# Implementation Plan — Movie Ticket Booking System

Use this document if you start a **new chat**, switch IDEs, or pick up the work later. It is the execution plan, not a rewrite of the product spec.

**Related docs**

- `docs/PROJECT.md` — original product/architecture spec (some sections are superseded; see §1 below)
- `docs/DBSchema.png` — ERD

**Stack (locked)**

- Backend: Core Java, JAX-RS (Jersey), Tomcat
- Frontend: HTML, CSS, JavaScript (not started)
- Persistence: JDBC against the schema in `DBSchema.png`
- Auth: Tomcat `HttpSession` (same-origin HTML on Tomcat)

---

## 1. Locked decisions (source of truth)

These override older wording in `PROJECT.md` where they conflict.

| Topic | Decision |
| --- | --- |
| Movies | **Seed data only.** Theatre admins do **not** create/update/delete movies. They only attach an existing movie to a show. |
| Auth | Working **register / login / logout**. Session stores `userId` + `role`. Customer ops vs admin ops are authorized. Admin theatre/screen/show ops also check **ownership** (`Screen → Theatre → admin_id`). |
| PENDING lifetime | **5 minutes** (`expires_at = now + 5 minutes`). Scheduler expires them and deletes `show_seats`. |
| Refund | **CONFIRMED** only. **No cancel after `start_time`.** More than 30 minutes before start → **100%** to customer (full reverse of admin credit). Within 30 minutes before start → **75%** to customer, **25%** stays in admin wallet. Integer math: `kept = total / 4`, `refund = total - kept`. |
| Show/screen mutation | **Update/delete show or screen is forbidden until the show has ended.** End = `start_time + movie.duration_minutes`. A screen is blocked while **any** of its shows has not ended. Theatre delete/update: block while any unfinished show exists under that theatre (same rule). |
| Money | **`int` rupees** in DB and Java. Not `BigDecimal`. |
| Seats | Sparse `show_seats`. Missing row = available. Composite PK `(show_id, row_label, seat_number)`. `booking_id` **NOT NULL**. |
| Payment | Simulated wallets, **same DB transaction** as booking/cancel. No payment gateways. |
| Concurrency | DB uniqueness + transaction. No `synchronized`. |
| Frontend seat click | Not a reservation. Claim happens only in `bookTickets`. |

**Assumed (not contradicted by the user)**

- Cancel after `start_time` is **rejected**.
- Browse movies / movie details can be allowed for any logged-in user (or public). Admin movie catalogue APIs are **not** exposed.

---

## 2. Current backend state

Code lives under `backend/src/` as **stubs and service sketches**. There is **no** Maven/Gradle WAR, `web.xml`, Jersey resources, JDBC, SQL, exceptions package, auth, or frontend.

### 2.1 What already matches the plan

- Layering: models, DTOs, services, concrete repositories (no repository interfaces).
- Domain types: `User`, `Theatre`, `Screen`, `Movie`, `Show`, `Booking`, `ShowSeat`.
- `BookingStatus`: `PENDING`, `CONFIRMED`, `CANCELLED`, `EXPIRED`.
- `BookingService` orchestration: validate customer → show → screen → movie → theatre admin → seats → PENDING booking → claim seats → payment → CONFIRMED.
- `expires_at` already set to **plus 5 minutes**.
- Sparse layout: `getAvailableSeats` generates seats from `row_range` + `seats_per_row` and marks rows present in `show_seat` as unavailable.
- `ShowSeatRepository.claimSeat` is documented as atomic insert / fail.
- `PaymentService` debit customer / credit admin (and reverse on refund).
- `ShowService.createShow` checks movie, screen, theatre, **admin ownership**, timing in the future.
- Theatre/screen/show services take `adminId` and `verifyOwnership`.
- No `SeatStatus`, no `booking_seat`, no separate admin/customer tables.

### 2.2 Deviations and gaps (must fix or implement)

**Compile / missing files**

- `enums.Role` is imported (`User`, `BookingService`) but **`Role.java` does not exist**.
- `ShowSeatRepository.claimSeat` has **no return** — will not compile.

**Money type (agreed: `int`)**

Still `BigDecimal` on: `User.walletBalance`, `Movie.ticketPrice`, `Booking.totalAmount`, `PaymentService`, `MovieRequest`, `MovieDetailsResponse`, `BookingResponse`, `UserRepository.updateWalletBalance`.

**Auth / HTTP**

- No `AuthService`, login/register DTOs, session filter, Jersey resources, Tomcat WAR layout.
- Customer/admin IDs are method arguments; nothing binds them to a session yet.

**Movies**

- `MovieService.createMovie` / `updateMovie` / `deleteMovie` still exist. **Do not expose them** on the API. Prefer keeping them unused or deleting them later; seed via SQL.

**Cancellation / payment rules**

- `cancelBooking` always refunds **100%**. Missing 30-minute window, 75/25 split, and reject-after-start.
- `PaymentService.refundPayment` only supports a **single full amount**. Needs `refundCustomer` + `debitAdmin` of **refund amount** only (admin keeps 25% when applicable).
- Refund currently fails if admin wallet `< full amount`. After 25% keep, debit admin only by the refunded portion. Decide: if admin wallet is too low, fail the cancel (strict) — keep this unless product says otherwise.

**Show / screen / theatre lifecycle**

- `ShowService.updateShow` / `deleteShow` do **not** require the show to have **ended**.
- `ScreenService.updateScreen` can change `row_range` / `seats_per_row` immediately (unsafe if shows exist).
- `ScreenService.deleteScreen` / `TheatreService.deleteTheatre` have no “unfinished shows” check.
- `validateShowTiming` does **not** check overlap with other shows on the same screen (`start` + duration).

**Booking extras**

- `bookTickets` does not reject booking a show that has already **started** (should reject).
- No expiry job for `PENDING` + `expires_at < now`.
- No shared **transaction** (one JDBC `Connection`, commit/rollback) wrapping booking + seats + wallets.

**Schema vs Java naming**

- ERD: `users.id`, `password_hash`, `shows.start_time`, `movies.certification`, `duration_minutes`.
- Java: `User.id` + `password`, `Show.showTiming`, `durationInMinutes`.
- Align JDBC column names to the ERD; map in repositories. Prefer documenting: Java `showTiming` ↔ column `start_time`; Java `password` field should hold **hash** or be renamed `passwordHash`.

**Repositories**

- All methods are comments / empty `Optional` / `List.of()`. `claimSeat` must use `INSERT` and treat unique-constraint violation as “not claimed”.

**Exceptions**

- `RuntimeException` / `IllegalArgumentException` everywhere. Plan: small `exception/` types + Jersey `ExceptionMapper` for HTTP 400/401/403/404/409.

---

## 3. Suggested package / WAR layout

```text
backend/
├── pom.xml                          # WAR, Java 17+, Jersey, servlet API, JDBC driver
├── src/main/java/
│   ├── model/
│   ├── dto/request/   dto/response/
│   ├── enums/                      # Role.java, BookingStatus.java
│   ├── exception/
│   ├── repository/
│   ├── service/                    # + AuthService, BookingExpiryJob
│   ├── filter/                    # AuthFilter (session)
│   ├── resource/                   # Jersey: Auth, Movie, Theatre, Screen, Show, Booking
│   └── config/                     # Jersey Application, DataSource
├── src/main/resources/
│   ├── db/schema.sql
│   └── db/seed.sql                 # movies + optional demo users
└── src/main/webapp/
    ├── WEB-INF/web.xml
    └── (later: static HTML/CSS/JS)
```

Today’s files sit in `backend/src/` without `main/java`. **First structural step:** move to Maven `src/main/java` (same packages) so Tomcat/Jersey can build a WAR.

---

## 4. Task list (execute in order)

### Phase 0 — Project skeleton

1. Add Maven WAR (`packaging: war`). Dependencies: Jersey (Servlet 3 container), Jackson (JSON), Servlet API (provided by Tomcat), JDBC driver for the chosen DB (MySQL or PostgreSQL — pick one and stick to `DBSchema.png` types).
2. Move existing Java sources to `src/main/java/...` preserving packages `model`, `dto`, `enums`, `service`, `repository`.
3. Add `JerseyApplication` (`ResourceConfig` / `Application`) and `web.xml` mapping `/api/*` to Jersey `ServletContainer`.
4. Add a `DataSource` (Tomcat JNDI or simple connection pool). One helper: `getConnection()` used by repositories.

### Phase 1 — Schema, types, and money

5. Write `schema.sql` from `docs/DBSchema.png` **plus** these constraints:
   - `users.email` **UNIQUE**
   - `show_seats` **PRIMARY KEY** `(show_id, row_label, seat_number)`
   - `show_seats.booking_id` **NOT NULL**
   - FKs as in the diagram
   - `bookings.status` / `users.role` as `VARCHAR` with app enums `CUSTOMER`/`ADMIN` and `PENDING`/`CONFIRMED`/`CANCELLED`/`EXPIRED`
6. `seed.sql`: several movies (price, duration, certification). Optional: one admin + one customer with known password hashes and wallet balances (for manual testing).
7. Add missing `enums/Role.java` (`CUSTOMER`, `ADMIN`).
8. Change money fields from `BigDecimal` to `int` in models, DTOs, `PaymentService`, repositories.
9. Store **password hashes** in `users.password_hash`. Hash on register (e.g. PBKDF2 or BCrypt if you add a small library; otherwise SHA-256 + salt is acceptable for this academic app — document which).
10. Implement `ShowSeatRepository.claimSeat`: `INSERT`; on unique violation return `false`; otherwise `true`. Never “select then insert” as the only check.

### Phase 2 — JDBC repositories

11. Implement every repository method with JDBC (`PreparedStatement`). Map ERD columns ↔ Java fields.
    - `UserRepository`: `findById`, `findByEmail`, `save`, `updateWalletBalance`
    - `TheatreRepository`: including `findByAdminId`, `findByMovieId` (theatres that have shows for that movie)
    - `ScreenRepository`, `MovieRepository` (read-only is enough for movies: `findById`, `findAll`; leave save/update/delete unimplemented or unused)
    - `ShowRepository`: + query shows on a screen overlapping a time window (for overlap checks)
    - `BookingRepository`: `save`, `findById`, `findByUserId`, `update`, `findExpiredPending(now)`
    - `ShowSeatRepository`: `claimSeat`, `findByShowId`, `findByBookingId`, `deleteByBookingId`
12. Pass `Connection` into repository methods **or** a `TransactionContext` ThreadLocal so one booking uses one connection.

### Phase 3 — Transactions

13. Add a small `TransactionManager`: `begin` / `commit` / `rollback` / `close`.
14. Wrap in **one** transaction:
    - `bookTickets`: insert booking PENDING → claim all seats → `processPayment` → update CONFIRMED
    - `cancelBooking`: compute refund split → wallet updates → delete `show_seats` → status CANCELLED
    - expiry job: status EXPIRED + delete `show_seats`
15. If any seat claim fails or wallet is insufficient: **rollback** the whole unit.

### Phase 4 — Align services with locked rules

16. **`PaymentService`**
    - `processPayment(customerId, adminId, amount)` using `int`.
    - `refundPayment(customerId, adminId, refundAmount)` — move **only** `refundAmount` from admin to customer (not always `total_amount`).
17. **`BookingService.bookTickets`**
    - Reject if `show.start_time` is not in the future (or already started).
    - Keep 5-minute `expires_at`.
    - Keep seat validation + duplicate check + movie price `×` seat count as `int`.
18. **`BookingService.cancelBooking`**
    - Must be owner + `CONFIRMED`.
    - Load show; if `now >= start_time` → reject.
    - If `now <= start_time - 30 minutes` → refund = total, kept = 0.
    - Else → `kept = total / 4`, `refund = total - kept`.
    - Call `refundPayment` with `refundAmount` only.
    - Delete `show_seats`; set `CANCELLED`.
19. **`BookingExpiryService` (or method on BookingService)**  
    Find `PENDING` and `expires_at < now`; for each (same transaction): set `EXPIRED`, `deleteByBookingId`. Do **not** refund (payment never completed).
20. Schedule expiry: `ServletContextListener` + `ScheduledExecutorService` (e.g. every 30–60 seconds). Interval is tuning; **5-minute hold** is the business rule.
21. **`ShowService`**
    - Overlap: no other show on the same screen whose `[start, start+duration)` intersects the new interval. Use movie duration of **each** show.
    - `updateShow` / `deleteShow`: allowed only if `now >= start_time + duration` of **this** show (ended). If you also change screen/movie/time on update, re-run overlap + ownership.
22. **`ScreenService`**
    - `updateScreen` / `deleteScreen`: if any show on that screen has `now < start + duration`, reject. Layout changes (`row_range`, `seats_per_row`) only when that passes.
23. **`TheatreService.deleteTheatre` / `updateTheatre`**
    - Reject delete (and optionally update) if any unfinished show exists under the theatre’s screens.
24. **`MovieService`**
    - Keep `browseMovies`, `getMovieDetails`, `getTheatresShowingMovie`.
    - Do **not** add Jersey routes for create/update/delete.

### Phase 5 — Exceptions and HTTP mapping

25. Add exceptions such as: `NotFoundException`, `ForbiddenException`, `UnauthorizedException`, `ConflictException` (seat taken), `ValidationException`, `InsufficientBalanceException`.
26. Replace generic `RuntimeException` in services incrementally as you touch methods.
27. Jersey `ExceptionMapper`s → JSON `{ "error": "..." }` and status codes: 400, 401, 403, 404, 409 (seat conflict).

### Phase 6 — Auth

28. DTOs: `RegisterRequest` (name, email, password, role **or** separate register-customer / register-admin), `LoginRequest`, `AuthResponse` (user id, name, role — no password).
29. `AuthService.register`: unique email, hash password, default `wallet_balance` (e.g. customer 10000, admin 0 — document in seed/plan).
30. `AuthService.login`: verify hash, `session.setAttribute("userId")`, `session.setAttribute("role")`.
31. `AuthService.logout`: `session.invalidate()`.
32. `AuthFilter` (servlet filter on `/api/*` except `/api/auth/login`, `/api/auth/register`, and optionally public `GET /api/movies`).
33. Inject current user into resources: read session; pass `userId` into services. Never trust `adminId`/`customerId` from the JSON body.

### Phase 7 — Jersey resources (controllers)

34. `AuthResource`: `POST /auth/register`, `POST /auth/login`, `POST /auth/logout`, `GET /auth/me`.
35. `MovieResource` (read-only): `GET /movies`, `GET /movies/{id}`, `GET /movies/{id}/theatres`.
36. `TheatreResource` (admin): create/update/delete `/theatres`; `GET` own theatres for admin.
37. `ScreenResource` (admin): create/update/delete under a theatre.
38. `ShowResource`: admin create/update/delete; `GET /movies/{id}/shows` or `GET /shows?movieId=`; `GET /screens/{id}/shows`; `GET /shows/{id}/seats` → `BookingService.getAvailableSeats`.
39. `BookingResource` (customer): `POST /bookings`, `GET /bookings` (mine), `GET /bookings/{id}`, `POST /bookings/{id}/cancel` (or `DELETE` with cancel semantics).
40. JSON: Jackson JavaTimeModule for `LocalDateTime`. Align show JSON field `startTime` vs `showTiming` — pick one name and use it in the frontend later.

### Phase 8 — Frontend (after API works)

41. Static pages under `src/main/webapp/`: login/register, movie list, movie details, shows, seat map, checkout confirmation, my bookings, admin theatre/screen/show forms.
42. `fetch('/api/...', { credentials: 'same-origin' })` so the session cookie is sent.
43. Seat UI: highlight selection locally; **POST booking** only on confirm. Do not call a hold API (there is none).
44. Show refund policy copy: 100% if >30 min before start, 75% inside 30 min, none after start.

### Phase 9 — Manual test checklist

45. Two browsers or two sessions: same seat on same show → one `CONFIRMED`, one 409/error; DB has one `show_seats` row.
46. Insufficient wallet → rollback; no `show_seats` leftover, booking not CONFIRMED.
47. Wait 5+ minutes on a stuck PENDING (force by stopping after insert in a test, or unit-test the expiry query) → EXPIRED, seats free.
48. Cancel >30 min before start → customer full amount, admin reversed fully.
49. Cancel inside 30 min → customer `total - total/4`, admin keeps `total/4`.
50. Cancel after start → rejected.
51. Admin A cannot update admin B’s screen/show.
52. Update/delete show before it ends → rejected; after end → allowed.
53. Customer cannot hit admin URLs; admin cannot `POST /bookings`.

---

## 5. API sketch (for implementers)

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| POST | `/api/auth/register` | public | |
| POST | `/api/auth/login` | public | |
| POST | `/api/auth/logout` | logged in | |
| GET | `/api/auth/me` | logged in | |
| GET | `/api/movies` | public or any user | |
| GET | `/api/movies/{id}` | public or any user | |
| GET | `/api/movies/{id}/theatres` | same | |
| GET | `/api/shows?movieId=` | same | |
| GET | `/api/shows/{id}/seats` | same | |
| POST | `/api/bookings` | CUSTOMER | |
| GET | `/api/bookings` | CUSTOMER | own |
| GET | `/api/bookings/{id}` | CUSTOMER | own |
| POST | `/api/bookings/{id}/cancel` | CUSTOMER | |
| POST | `/api/theatres` | ADMIN | |
| PUT/DELETE | `/api/theatres/{id}` | ADMIN + owner | |
| POST | `/api/screens` | ADMIN + owner | |
| PUT/DELETE | `/api/screens/{id}` | ADMIN + owner | ended-shows rule |
| POST | `/api/shows` | ADMIN + owner | |
| PUT/DELETE | `/api/shows/{id}` | ADMIN + owner | ended-show rule |

Do **not** add `POST /api/movies`.

---

## 6. What not to implement

- Movie genres, actors, languages, VIP/dynamic pricing, real payment gateways, coupons, taxes, food, reviews, loyalty
- `SeatStatus` table, `booking_seat`, separate `admins`/`customers` tables
- Java `synchronized` for seats
- Persisting frontend “selected” seats
- Spring Boot / Spring Security (stack is Jersey + Tomcat)

---

## 7. Suggested next action

Start at **Phase 0** (Maven WAR + move sources), then **Phase 1** (`Role.java`, `int` money, `schema.sql` + seed). Services are already a useful sketch; do not treat them as finished until Phases 3–4 are done.
