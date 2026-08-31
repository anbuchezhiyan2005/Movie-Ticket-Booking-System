# Backend Status

Last updated: 2026-08-28

This document records the backend behavior that is implemented and the behavior that has been verified. A feature is marked as **tested** only when it has been exercised by an automated test or a successful runtime request.

## Current Runtime State

The backend currently runs as a Maven WAR deployed to Tomcat 10. It connects to MySQL using the configuration in `backend/src/main/resources/db.properties`.

Verified runtime setup:

- Maven WAR packaging succeeds.
- Tomcat deploys the WAR.
- Jersey starts and discovers REST resources.
- `GET /api/health` responds successfully.
- Database initialization runs during application startup.
- MySQL connectivity works.
- Jersey dependency injection constructs repositories and services.
- Registration and login endpoints respond successfully.
- Tomcat session cookies work across authenticated requests.
- Opt-in HTTP smoke tests pass against the deployed Tomcat API.
- The static frontend includes role-gated admin management controls.

## Implemented Business Logic

### Authentication and users

- Users can register with a name, email, and password.
- Passwords are stored as BCrypt hashes.
- New customers receive a starting wallet balance of 10,000 rupees.
- Login validates the email and password.
- Login stores the user ID and role in the Tomcat HTTP session.
- Logout invalidates the session.
- The current user endpoint returns the logged-in user's profile.
- Public registration accepts only the `CUSTOMER` role.
- Admin and customer roles are enforced by request helpers and resources.
- Invalid or incomplete sessions are rejected as unauthenticated.

### Movies and shows

- Movies are read-only catalogue data loaded from `seed.sql`.
- The API supports browsing movies, movie details, and theatres showing a movie.
- Shows can be queried by movie and screen.
- Available seats are derived from the screen layout and claimed `show_seats` rows.
- Admins can create, update, and delete shows when ownership and lifecycle rules allow it.
- Show timing validation rejects past shows and overlapping shows on the same screen.

### Theatre and screen management

- Admins can manage their own theatres.
- Admins can manage screens belonging to their theatres.
- Ownership is checked through the theatre -> screen -> show relationship.
- Screen and theatre mutation rules consider unfinished shows.
- Show, screen, and theatre operations are role-restricted.

### Booking and payment

- Only customers can create bookings.
- A booking must contain at least one valid seat.
- Invalid rows, invalid seat numbers, and duplicate seats are rejected.
- Shows that have already started cannot be booked.
- Booking price is calculated as movie ticket price multiplied by seat count.
- Bookings initially use a five-minute pending expiry window.
- Seat claims use a database uniqueness constraint and atomic insert behavior.
- Booking, seat claims, payment, and confirmation run in one database transaction.
- Failed seat claims or insufficient balance roll back the transaction.
- Customer and admin wallet movements are simulated in the database.
- Customers can view only their own bookings.
- Only confirmed bookings can be cancelled.
- Cancellation after show start is rejected.
- Cancellation more than 30 minutes before the show gives a full refund.
- Cancellation within 30 minutes gives a 75% refund; the admin keeps 25%.
- Pending bookings can expire and release their claimed seats through the scheduler.

### HTTP behavior

- Application exceptions are converted to JSON error responses.
- Validation errors use HTTP 400.
- Authentication failures use HTTP 401.
- Authorization failures use HTTP 403.
- Missing resources use HTTP 404.
- State conflicts such as an already-claimed seat use HTTP 409.
- Movie mutation routes are not exposed.

## Verified Tests and Requests

The following checks have passed:

### Automated tests

Command:

```text
mvn test
```

Result on 2026-08-28:

```text
Tests run: 10
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

Covered by automated tests:

- Full refund more than 30 minutes before show time.
- 75% refund at the 30-minute boundary.
- Integer refund calculation for an odd total.
- Concurrent claims for the same seat result in exactly one successful claim.
- Insufficient wallet balance rolls back the booking and claimed seat.
- Expired pending bookings are marked `EXPIRED` and release their claimed seats.
- Full refunds transfer the entire requested amount from admin to customer.
- Partial refunds transfer only 75%, leaving 25% with the admin.
- Confirmed cancellation reverses wallets, changes status to `CANCELLED`, and releases the seat.
- Cancellation after show start is rejected without changing the booking.

### Manual runtime checks

Successfully verified through PowerShell and the deployed API:

- Customer registration succeeds.
- Admin login succeeds.
- Customer login succeeds.
- `GET /api/auth/me` returns the logged-in user's details.
- An unauthenticated booking request returns `Not authenticated`.
- An admin calling the customer booking endpoint returns `Customer role required`.
- Public admin registration is rejected with `Public registration is only available for customers`.
- The health endpoint responds after Tomcat deployment.

### HTTP integration tests

The opt-in HTTP suite uses Java `HttpClient` with cookie sessions and calls the deployed Tomcat/Jersey application:

```text
mvn '-Drun.http.tests=true' '-Dtest=ApiHttpSmokeTest' test
```

Result on 2026-08-28:

```text
Tests run: 2
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

Verified through the HTTP layer:

- Tomcat health response.
- Seeded movie catalogue response.
- Customer registration response and starting wallet.
- Login response and session cookie retention.
- `/auth/me` session identity.
- Logout and subsequent protected-request `401` response.

## Not Yet Fully Tested

These behaviors are implemented or partially implemented but still need automated or complete end-to-end verification:

- Admin A cannot modify Admin B's theatre, screen, or show.
- Show update/delete restrictions before and after the show ends.
- Screen and theatre update/delete restrictions while shows are unfinished.
- Repository mappings for every entity and query.
- Complete endpoint-level authorization matrix.
- HTTP booking, cancellation, and HTTP concurrency flows.
- Browser verification of admin CRUD and customer discovery after admin setup.
- Tomcat shutdown cleanly stops the expiry scheduler.
- Production-safe database credentials and deployment configuration.

## Current Functional Capability

With MySQL initialized from `schema.sql` and `seed.sql`, and the WAR deployed to Tomcat, the system can currently:

1. Register customers.
2. Log users in and maintain sessions.
3. Log users out and retrieve the current profile.
4. Browse the seeded movie catalogue.
5. View movie details and theatres showing a movie.
6. View shows and available seats.
7. Allow admins to manage theatres, screens, and shows subject to ownership rules.
8. Allow customers to book seats and pay from a simulated wallet.
9. Prevent two concurrent bookings from claiming the same seat.
10. Allow customers to view and cancel their own confirmed bookings according to the refund policy.
11. Expire pending bookings and release their seats through the background scheduler.
12. Return structured JSON errors for the supported application exceptions.

The backend is not considered production-ready until the untested transaction, authorization, lifecycle, and deployment scenarios above are covered.

## Demo Frontend

A static same-origin demo is now implemented under `backend/src/main/webapp`:

- `index.html` — application shell and accessible controls.
- `styles.css` — responsive catalogue, authentication, seats, and booking presentation.
- `app.js` — registration, login, logout, movie browsing, movie details, shows, seat selection, booking, booking list, and cancellation.

Admin-facing capabilities now included:

- Create, edit, list, and delete owned theatres.
- Select a theatre and create, edit, list, and delete its screens.
- Select a screen and create, edit, list, and delete its shows.
- Choose seeded movies and future show times when creating shows.
- Display backend validation, overlap, ownership, and lifecycle errors.
- Hide admin controls from customer and guest sessions.

Customer-facing setup and booking capabilities remain available:

- Browse seeded movies and open movie details.
- View shows created by an admin.
- View available seats, select seats locally, book, view bookings, and cancel.
- Keep booking controls and booking history limited to customer sessions.
- Customer sessions include a Wallet tab showing profile details, role, email, and current balance.
- The Wallet tab refreshes from `/auth/me` after booking and cancellation to show the database-backed balance change.
- Admin sessions use the same Wallet tab to show booking credits and retained cancellation amounts.
- Admins can manually refresh their wallet after a customer transaction from another session.

The demo uses relative `/api` requests and browser cookies. It does not implement seat holds, movie CRUD, or real payment gateways. Redeploy the newly built `backend/target/movie-booking.war` to Tomcat before opening the demo.

Latest verified implementation additions:

- Admin screen selection loads the selected screen's shows without a page refresh.
- Theatre, screen, and show updates reject resources that already have bookings.
- Customer seat availability refreshes immediately after cancellation and every 10 seconds while viewing a show.
- Show times use local `Start time - End time` display based on movie duration.

The latest browser behavior requires redeploying the newest WAR after these changes.
