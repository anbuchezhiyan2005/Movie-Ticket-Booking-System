# Movie Ticket Booking System: Master Workflow and Design

This is the master reference for understanding the current Movie Ticket Booking System from the first browser request through logout. It describes the operational workflow, code flow, data model, security boundaries, concurrency mechanisms, background work, simulation mode, and known limitations.

The document reflects the current implementation and should be updated when behavior, endpoints, persistence, or concurrency rules change.

## 1. System Purpose

The application supports two roles:

```text
ADMIN
  -> owns theatres
  -> manages screens
  -> manages shows

CUSTOMER
  -> registers and logs in
  -> browses movies
  -> finds shows
  -> views seats
  -> holds and books seats
  -> pays from a wallet
  -> views bookings
  -> cancels eligible bookings
  -> receives a refund
  -> logs out
```

The main business relationship is:

```text
Admin
  -> Theatre
      -> Screen
          -> Show
              -> Movie
              -> Show seats
                  -> Booking
                      -> Customer
```

Movies are global catalogue records. An admin does not own a movie; an admin owns theatres, and shows connect movies to screens in those theatres.

## 2. Runtime Architecture

```text
Browser or simulation HTTP client
              |
              v
Tomcat 10 + Jakarta Servlet
              |
              v
AuthFilter
  - public route rules
  - HTTP session validation
  - CSRF validation
              |
              v
Jersey REST resources
              |
              v
DTOs and request mapping
              |
              v
Services
  - business validation
  - authorization
  - workflow orchestration
  - transactions
              |
       +------+------+
       |             |
       v             v
Seat cache       Repositories
                       |
                       v
                    MySQL
```

### Main technology components

| Component | Current implementation |
|---|---|
| Runtime | Java 17 |
| Build | Maven |
| Deployment | WAR named `movie-booking` |
| Web server | Apache Tomcat 10 |
| REST | Jersey 3.1.10 |
| Dependency injection | Jersey HK2 |
| JSON | Jackson for backend HTTP DTOs; `org.json` in the standalone simulator |
| Database | MySQL database `movie_booking` |
| Persistence | JDBC repositories |
| Passwords | BCrypt hashes through jBCrypt |
| Browser | Static HTML, CSS, and JavaScript in `backend/src/main/webapp` |
| Sessions | Tomcat `HttpSession` and cookies |
| Cache | Java concurrent collections in `SeatAvailabilityCache` |
| Scheduling | Java scheduled executors |

## 3. Project Layout

```text
movie-ticket-booking-system/
  backend/
    pom.xml
    src/main/java/
      cache/
      config/
      dto/
      enums/
      exception/
      filter/
      model/
      repository/
      resource/
      service/
      util/
    src/main/resources/
      db.properties
      db/schema.sql
      db/seed.sql
    src/main/webapp/
      index.html
      app.js
      styles.css
      WEB-INF/web.xml
    src/test/
  docs/
  Simulation.java
  CustomerSimulation.java
  SimulationLogger.java
  simulation.ps1
```

The standalone simulator is outside `backend` and communicates with the deployed application over HTTP. It does not call backend Java services directly.

## 4. Application Startup

### 4.1 Browser/Tomcat deployment

The backend is packaged as a WAR and deployed to Tomcat. `WEB-INF/web.xml` configures:

- Jakarta servlet encoding
- HTTP session settings
- `AuthFilter`
- Jersey servlet mapping at `/api/*`
- HTTPS enforcement through `transport-guarantee=CONFIDENTIAL`

The deployed base URL is commonly:

```text
https://localhost:8443/movie-booking
```

The REST API base URL is:

```text
https://localhost:8443/movie-booking/api
```

### 4.2 Application initialization

`AppLifecycleListener` initializes environment configuration and the database.

`JerseyApplication` then:

1. Registers the `resource` and `exception` packages.
2. Registers Jackson JSON support.
3. Creates the singleton `SeatAvailabilityCache`.
4. Starts cache cleanup.
5. Binds repositories and services through HK2.
6. Binds the cache singleton.
7. Registers `RuntimeLifeCycle`.

`RuntimeLifeCycle` starts `BookingExpiryScheduler` after construction and stops it during application shutdown.

### 4.3 Database connections and time

`Database` loads connection settings from `db.properties`, system properties, or environment configuration. Each newly opened JDBC connection sets its MySQL session timezone to UTC:

```sql
SET time_zone = '+00:00'
```

A transaction connection is stored in a Java `ThreadLocal`. This means repository calls made on the same Java thread use the same transaction connection.

## 5. First Browser Visit

The browser requests the static application shell. The frontend files are served from the WAR:

```text
GET /movie-booking/index.html
GET /movie-booking/app.js
GET /movie-booking/styles.css
```

The browser does not have a customer session yet. Public routes are allowed through the filter, while protected routes require a valid Tomcat session.

The frontend uses relative API requests. Its JavaScript builds the API base from the application context path, so it can call routes such as:

```text
/api/auth/login
/api/movies
/api/shows
```

## 6. Authentication and Session Flow

### 6.1 Customer registration

Endpoint:

```text
POST /api/auth/register
```

Required customer fields:

```json
{
  "name": "Customer Name",
  "email": "customer@example.com",
  "password": "password123"
}
```

The public customer registration path forces the role to `CUSTOMER`. It does not allow a caller to create an admin through this route.

`AuthService`:

1. Validates name, email, optional phone number, and password length.
2. Normalizes the email.
3. Rejects duplicate email addresses.
4. Hashes the password with BCrypt.
5. Assigns the customer starting wallet balance of `10000`.
6. Saves the user.
7. Returns an authentication response and CSRF token for the initial session.

The standalone simulation registers unique users before starting customer worker threads.

### 6.2 Login

Endpoint:

```text
POST /api/auth/login
```

The request contains email and password. `AuthService` loads the user by normalized email and verifies the BCrypt password hash.

On success, `AuthResource`:

1. Stores `userId` in the server-side `HttpSession`.
2. Stores the user role in the session.
3. Issues a CSRF token in the session.
4. Returns the user profile, wallet balance, and CSRF token.

The browser or simulator receives a session cookie, normally a `JSESSIONID`. Later requests must send both:

```text
Session cookie -> identifies the logged-in session
X-CSRF-Token   -> authorizes state-changing requests
```

The simulator gives every customer its own `CookieManager` and `HttpClient`, so customer sessions do not overlap.

### 6.3 AuthFilter behavior

`AuthFilter` runs before protected resources:

1. Allows public GET routes such as movies, shows, seats, health, and static assets.
2. Allows public POST registration and login.
3. Reads `userId` and `role` from the HTTP session.
4. Returns HTTP 401 if session identity is missing or invalid.
5. For POST, PUT, PATCH, and DELETE requests, compares `X-CSRF-Token` with the token in the session.
6. Returns HTTP 403 if the CSRF token is missing or wrong.
7. Places validated identity attributes on the request for downstream code.

`RequestUsers` provides helpers such as requiring any user, requiring a customer, and requiring an admin.

### 6.4 Logout

Endpoint:

```text
POST /api/auth/logout
```

The server invalidates the HTTP session and returns HTTP 204. The simulator additionally clears its local cookie store and CSRF token.

## 7. Movie and Show Discovery

### 7.1 Browse movies

```text
GET /api/movies
```

`MovieResource` calls `MovieService.browseMovies()`. The response contains catalogue fields such as:

```text
movieId
movieName
certification
durationInMinutes
```

The ticket price is stored on the movie record and is not supplied by the customer.

### 7.2 Movie details

```text
GET /api/movies/{movieId}
```

Returns detailed movie information.

### 7.3 Theatres and shows

```text
GET /api/movies/{movieId}/theatres
GET /api/shows?movieId={movieId}
GET /api/screens/{screenId}/shows
```

`ShowService` validates show timing and relationships. A show connects a movie to a screen. The screen connects to a theatre, and the theatre identifies its admin owner.

### 7.4 Fixed-show simulation

The simulator is configured with one movie title and one show ID. It still calls the discovery endpoints to verify that the configured movie and show exist before attempting seat operations.

## 8. Seat Layout and Availability

Physical seats are not stored as permanent seat rows. A screen stores a compact layout:

```text
row_range
seats_per_row
```

The application generates the seat grid from the screen layout. A `show_seats` row exists only when a seat is claimed by a booking.

Seat status is derived:

```text
No show_seats row
  -> AVAILABLE

show_seats row linked to AWAITING_OTP or PENDING booking
  -> HELD

show_seats row linked to CONFIRMED booking
  -> BOOKED
```

Endpoint:

```text
GET /api/shows/{showId}/seats
```

The response includes every generated seat and its current availability/status.

## 9. Core Booking Flow

The active booking flow uses OTP routes. The direct `POST /api/bookings` and direct cancellation route are intentionally blocked with validation errors because email verification is required.

### 9.1 Request a booking hold

Endpoint:

```text
POST /api/bookings/otp/request
```

Payload:

```json
{
  "showId": 544,
  "seats": [
    { "rowLabel": "A", "seatNumber": 1 },
    { "rowLabel": "B", "seatNumber": 3 }
  ]
}
```

`OtpResource` first requires a customer session, then calls `BookingService.holdTickets()`.

The service:

1. Validates the request.
2. Requires a customer role.
3. Loads the future show.
4. Loads screen, movie, theatre, and theatre admin.
5. Validates row and seat ranges.
6. Rejects duplicate seats.
7. Calculates total price as:

```text
movie.ticket_price * number_of_selected_seats
```

8. Creates a booking with status `AWAITING_OTP` and a five-minute expiry.
9. Inserts `show_seats` rows for the selected seats.
10. Updates the seat cache to `HELD`.
11. Returns booking details to the OTP resource.

The resource then creates an OTP challenge and either:

```text
Production mode:
  sends the OTP through SMTP

Simulation mode:
  skips SMTP and includes simulationOtp in the response
```

If email delivery fails in production, the hold is released and the request fails.

### 9.2 Confirm booking OTP and payment

Endpoint:

```text
POST /api/bookings/{bookingId}/otp/verify
```

Payload:

```json
{
  "challengeToken": "...",
  "code": "123456"
}
```

`BookingService.confirmHeldBookingWithOtp()` verifies the OTP inside the same database transaction as confirmation/payment.

The transaction:

```text
Lock booking row
  -> validate ownership and AWAITING_OTP status
  -> validate hold expiry
  -> load claimed seats
  -> debit customer wallet
  -> credit theatre/admin wallet
  -> set booking CONFIRMED
  -> clear expires_at
  -> mark cached seats BOOKED
  -> issue gate token
  -> commit
```

After commit, a confirmation email is queued asynchronously. A confirmation email failure is logged and does not undo the already committed booking.

### 9.3 OTP challenge details

An OTP challenge contains:

```text
challenge ID
token
OTP code
expiry time
resend availability time
```

The database stores hashes of the token and OTP. Verification checks user, booking, purpose, expiry, attempt count, and code. The challenge is consumed after successful verification.

Production does not return the plain OTP. Simulation mode returns `simulationOtp` only to support the standalone simulator.

## 10. Cancellation and Refund Flow

### 10.1 Request cancellation OTP

Endpoint:

```text
POST /api/bookings/{bookingId}/cancel/otp/request
```

The service checks ownership, `CONFIRMED` status, and show start time. It creates a cancellation-purpose OTP challenge.

### 10.2 Verify cancellation OTP

Endpoint:

```text
POST /api/bookings/{bookingId}/cancel/otp/verify
```

The response is HTTP 204 with no body. The service transaction:

```text
Lock booking row
  -> validate ownership and status
  -> reject cancellation after show start
  -> calculate refund
  -> debit admin wallet
  -> credit customer wallet
  -> delete show_seats rows
  -> set booking CANCELLED
  -> remove seats from cache
  -> commit
```

The gate token is checked by the gate validation service against booking state, so a cancelled booking cannot be redeemed even if its token record remains issued.

Refund rules:

```text
More than 30 minutes before show: full refund
Within 30 minutes:                 75% refund
After show start:                  rejected
```

## 11. Concurrency and Seat Safety

### 11.1 Application-level locks

`BookingService` uses an in-memory `ConcurrentHashMap` of active seat lock keys. The key includes show ID, row, and seat number.

This reduces same-JVM races but is not a distributed lock. It does not coordinate separate Tomcat instances.

A rejected lock attempt produces a conflict before the database transaction starts. The simulator treats this as retryable.

### 11.2 Database protection

`show_seats` has a composite primary key:

```text
(show_id, row_label, seat_number)
```

This is the final durable no-double-booking guard. If two transactions race, only one can insert a given seat.

A request for multiple seats is all-or-nothing. If any selected seat cannot be claimed, the transaction rolls back the complete request.

### 11.3 Seat-order and deadlocks

Seat claims should be normalized and processed in a consistent order. This reduces circular waits between transactions selecting overlapping seats in different orders.

The system has observed MySQL deadlocks during high contention. A deadlock is different from a normal seat conflict:

```text
409 conflict -> expected seat contention
500 deadlock  -> transient database transaction failure
```

The database uniqueness constraint remains necessary even if application-level locking is strengthened.

### 11.4 Wallet transaction behavior

Wallet debit and credit operations are performed in the booking/cancellation transaction. Insufficient balance causes rollback. A successful payment changes both customer and admin wallet balances atomically.

## 12. Cache and Background Processing

### 12.1 SeatAvailabilityCache

`SeatAvailabilityCache` is a local performance layer:

- Cache entries are stored per show.
- Occupied seat states are held in concurrent maps.
- Temporary holds receive an in-memory TTL.
- Individual seats are added, changed, or removed surgically.
- Cache misses rebuild state from `show_seats` and booking status.
- A cleanup executor removes expired cache entries.

The cache is not authoritative. MySQL remains the source of truth.

### 12.2 BookingExpiryScheduler

The scheduler runs approximately every 30 seconds. It calls `BookingService.expirePendingBookings()`.

It finds expired `PENDING` and `AWAITING_OTP` bookings and:

1. Deletes claimed seats.
2. Invalidates active OTP challenges.
3. Sets status to `EXPIRED`.
4. Clears `expires_at`.
5. Removes seats from the cache.

This is how abandoned OTP holds eventually release seats.

### 12.3 Confirmation email executor

Confirmation email delivery is queued to a single daemon executor. Booking confirmation is not rolled back if the email queue or SMTP delivery later fails.

## 13. Admin Workflow

An admin registers through the protected admin-registration policy using the configured admin secret. Admin login creates the same session and CSRF foundation as customer login.

Admin operations:

```text
POST /api/theatres
PUT  /api/theatres/{id}
DELETE /api/theatres/{id}

GET  /api/screens/theatre/{theatreId}
POST /api/screens
PUT  /api/screens/{id}
DELETE /api/screens/{id}

POST /api/shows
PUT  /api/shows/{id}
DELETE /api/shows/{id}
```

Ownership is checked through the theatre-to-screen-to-show relationship. Admins cannot mutate another admin's theatre hierarchy. Show timing, overlap, dependency, and lifecycle rules protect existing booking data.

## 14. Gate Entry Workflow

When a confirmed booking is created, a gate token is issued until the show end time.

Scanner endpoint:

```text
POST /api/scanner/tickets/redeem
```

The scanner must provide `X-Scanner-API-Key`. The gate service:

1. Verifies the QR token signature.
2. Hashes the token.
3. Atomically consumes the token.
4. Rejects reused, expired, cancelled, ended, or invalid tickets.

A token is separate from the booking OTP. OTP confirms the booking or cancellation; the gate token proves entry at the venue.

## 15. Error Handling

Typed application exceptions are mapped to HTTP responses:

```text
400 validation/business rule failure
401 unauthenticated
403 forbidden/role/ownership failure
404 resource not found
409 state conflict, commonly seat already claimed
500 unexpected server/database failure
```

Important distinction:

```text
Expected seat contention should be handled as a normal retryable outcome.
Database deadlocks are transient infrastructure/transaction failures and need separate retry or transaction-order handling.
```

## 16. Standalone Simulation

The simulator lives outside the backend:

```text
Simulation.java
CustomerSimulation.java
SimulationLogger.java
```

`Simulation.java`:

- Configures customer count, base URL, movie, show, wallet, and ticket estimate.
- Registers unique test customers.
- Creates a thread pool.
- Submits one worker per customer.
- Collects `CustomerResult` values.

`CustomerSimulation.java`:

- Owns one HTTP session and cookie store.
- Logs in and retains CSRF state.
- Discovers the configured movie/show.
- Reads the complete seat map.
- Randomly selects seats.
- Requests and verifies booking OTP.
- Tracks expected wallet changes.
- Optionally cancels and verifies cancellation OTP.
- Logs out.

Simulation mode is selected by:

```text
OTP_DELIVERY_MODE=SIMULATION
```

In simulation mode, the backend skips SMTP and includes `simulationOtp` in the OTP response. Normal OTP verification still runs.

The simulator writes events through a queue-backed `SimulationLogger`, allowing many worker threads to log while one logger thread writes the file.

## 17. Simulation Workflow

```text
Register N test customers
  -> Start N worker threads
  -> Login independently
  -> Find configured movie
  -> Find configured show
  -> Read full seat map

If AVAILABLE seats exist:
  -> randomly choose seats
  -> request hold/booking OTP
  -> on 409 conflict, wait and retry
  -> verify OTP
  -> debit expected wallet amount
  -> wait for observation
  -> randomly cancel or keep booking
  -> if cancelled, verify refund and refresh seat map
  -> logout

If only HELD seats exist:
  -> wait and refresh until retry timeout

If only BOOKED seats remain:
  -> logout and finish without booking
```

Each customer has a separate cookie store. The queue logger serializes file writes, but the booking requests remain concurrent.

## 18. Database State Lifecycle

### User

```text
register -> users row created
login    -> session created; database row is read
logout   -> session invalidated; user row remains
```

### Booking

```text
AWAITING_OTP
  -> CONFIRMED
  -> CANCELLED

AWAITING_OTP/PENDING
  -> EXPIRED
```

### Seats

```text
no show_seats row -> AVAILABLE
show_seats + AWAITING_OTP/PENDING -> HELD
show_seats + CONFIRMED -> BOOKED
cancellation/expiry -> show_seats row deleted -> AVAILABLE
```

### OTP

```text
created -> active
verified -> consumed
resend -> old challenge superseded, new challenge created
expired booking -> active challenge invalidated
```

### Gate token

```text
booking confirmed -> ISSUED
scanner redemption -> USED
cancelled/expired/ended booking -> rejected by validation
```

## 19. Files by Responsibility

| Responsibility | Current file or directory |
|---|---|
| Deployment/build | `backend/pom.xml`, `backend/src/main/webapp/WEB-INF/web.xml` |
| Application wiring | `backend/src/main/java/config/JerseyApplication.java` |
| Startup/shutdown | `backend/src/main/java/config/AppLifecycleListener.java`, `RuntimeLifeCycle.java` |
| Authentication | `resource/AuthResource.java`, `service/AuthService.java`, `filter/AuthFilter.java`, `util/RequestUsers.java` |
| Frontend | `backend/src/main/webapp/index.html`, `app.js`, `styles.css` |
| Movie catalogue | `resource/MovieResource.java`, `service/MovieService.java`, `repository/MovieRepository.java` |
| Shows/seats | `resource/ShowResource.java`, `service/ShowService.java`, `service/BookingService.java` |
| Booking/cancellation | `resource/OtpResource.java`, `resource/BookingResource.java`, `service/BookingService.java` |
| Seat persistence | `repository/ShowSeatRepository.java` |
| Booking persistence | `repository/BookingRepository.java` |
| OTP | `service/OtpService.java`, `service/OtpEmailService.java`, `repository/OtpChallengeRepository.java` |
| Payment | `service/PaymentService.java`, `repository/UserRepository.java` |
| Gate entry | `resource/GateResource.java`, `service/GateValidationService.java`, `service/GateTokenService.java` |
| Cache | `cache/SeatAvailabilityCache.java`, `cache/CachedShowSeats.java` |
| Database | `config/Database.java`, `src/main/resources/db/schema.sql`, migrations, `seed.sql` |
| Simulation | `Simulation.java`, `CustomerSimulation.java`, `SimulationLogger.java` |
| Tests | `backend/src/test/java`, `backend/src/test/frontend` |

## 20. Known Gaps and Design Risks

These are important when debugging or redesigning the system:

1. **In-memory locks are not distributed.** Multiple Tomcat instances require database-level coordination or a distributed lock.
2. **Database deadlocks can still occur under high contention.** Consistent seat order reduces risk but does not eliminate every deadlock.
3. **A booking hold and OTP window are coupled.** Abandoned `AWAITING_OTP` bookings depend on the expiry scheduler to release seats.
4. **HTTP booking/concurrency coverage is less complete than service/repository coverage.** The standalone simulator provides additional runtime evidence.
5. **The simulator manually predicts wallet changes.** The server/database response is authoritative, especially for ticket price and refund amount.
6. **Gate tokens need lifecycle review.** Cancellation must be rejected at validation time even if the token row remains issued.
7. **The cache is local and derived.** Cache bugs must not be treated as durable booking truth.
8. **Email delivery is asynchronous for confirmations.** A successful booking does not guarantee confirmation-email delivery.
9. **HTTPS local development requires Java truststore configuration.** Browser trust and Java trust are separate.
10. **Simulation data requires cleanup.** Test users, bookings, seats, OTPs, and related records should be removed between controlled runs.

## 21. First-Principles Mental Model

The system can be understood through five invariants:

### Identity invariant

Every protected action must resolve to one authenticated user and role through the HTTP session and CSRF checks.

### Ownership invariant

A customer may only read or mutate their own bookings. An admin may only mutate theatres and descendants they own.

### Seat uniqueness invariant

For one show, one physical seat can have at most one `show_seats` row:

```text
(show_id, row_label, seat_number) is unique
```

### Transaction invariant

Booking state, seat claims, and wallet movement must commit together or roll back together.

### Lifecycle invariant

A seat claim must eventually be in one of these states:

```text
HELD while awaiting completion
BOOKED after payment confirmation
AVAILABLE again after cancellation or expiry
```

When debugging, identify which invariant is violated first. That usually identifies the owning layer:

```text
Identity -> filter/session/auth
Ownership -> RequestUsers/service authorization
Seat uniqueness -> BookingService/repository/schema
Atomicity -> Database transaction/service/payment
Lifecycle -> expiry scheduler/cache/cancellation
```
