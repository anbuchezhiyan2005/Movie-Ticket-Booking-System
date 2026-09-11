# Booking Flow and Concurrency

This document explains what happens when a customer selects seats and books a show, and how the application protects the process when many customers try to book at the same time.

## 1. Customer View of the Flow

The normal customer journey is:

```text
Login
  -> Browse movies
  -> Select a movie
  -> Select a show
  -> View the seat map
  -> Select available seats
  -> Request booking verification
  -> Seats are temporarily held
  -> Enter OTP
  -> Payment is processed
  -> Booking becomes confirmed
  -> Optionally cancel and receive a refund
  -> Logout
```

The application currently requires email/OTP verification for booking and cancellation. The direct booking endpoints deliberately return a validation error when called without verification.

## 2. Finding the Show and Seats

The browser first requests the shows for a movie:

```text
GET /api/shows?movieId={movieId}
```

It then requests the seat map:

```text
GET /api/shows/{showId}/seats
```

The response describes every seat. A seat can be:

```text
AVAILABLE  - the customer may select it
HELD       - another incomplete booking currently has it
BOOKED     - a confirmed booking owns it
```

The seat map is a view of the current state. It is not a permanent reservation. A seat can be available when the screen is displayed and unavailable by the time the booking request reaches the server.

## 3. Creating the Temporary Hold

The booking simulator and the frontend use:

```text
POST /api/bookings/otp/request
```

The request contains the show and selected seats:

```json
{
  "showId": 544,
  "seats": [
    { "rowLabel": "A", "seatNumber": 1 },
    { "rowLabel": "B", "seatNumber": 3 }
  ]
}
```

The server then:

1. Checks that the customer is authenticated.
2. Checks that the show has not started.
3. Loads the screen, movie, theatre, and ticket price.
4. Validates the selected rows and seat numbers.
5. Rejects duplicate seats in the request.
6. Calculates the booking total from the movie's database ticket price.
7. Creates a booking with status `AWAITING_OTP`.
8. Inserts one row into `show_seats` for every selected seat.
9. Marks those seats as held in the in-memory cache.
10. Creates an OTP challenge.
11. Sends the OTP by email, or returns it in simulation mode.

The booking is not paid yet. The customer has only created a temporary hold.

If OTP delivery fails, the application releases the hold and fails the request.

## 4. Confirming the Booking and Payment

The customer submits the challenge token and OTP code:

```text
POST /api/bookings/{bookingId}/otp/verify
```

The server verifies:

1. The challenge belongs to the current user.
2. The challenge belongs to the requested booking.
3. The challenge purpose is `BOOKING`.
4. The challenge has not expired or been consumed.
5. The attempt limit has not been exceeded.
6. The submitted code matches the stored hash.

If verification succeeds, the same database transaction:

```text
Debit customer wallet
  -> Credit theatre/admin wallet
  -> Change booking to CONFIRMED
  -> Clear booking expiry
  -> Mark cache seats BOOKED
  -> Commit
```

If payment fails, the transaction is rolled back. The booking and seat changes should not remain partially applied.

## 5. Cancellation and Refund

Cancellation also uses two steps:

```text
POST /api/bookings/{bookingId}/cancel/otp/request
POST /api/bookings/{bookingId}/cancel/otp/verify
```

After successful cancellation verification, the server:

1. Confirms ownership of the booking.
2. Requires the booking to be `CONFIRMED`.
3. Rejects cancellation after the show has started.
4. Calculates the refund from the booking total and show time.
5. Debits the theatre/admin wallet.
6. Credits the customer wallet.
7. Deletes the booking's `show_seats` rows.
8. Changes the booking to `CANCELLED`.
9. Removes the seats from the cache.
10. Commits the transaction.

The refund rules are currently:

```text
More than 30 minutes before the show: 100% refund
Within 30 minutes before the show:    75% refund
After the show starts:                 cancellation rejected
```

## 6. Database Seat Protection

The table `show_seats` has this primary key:

```text
(show_id, row_label, seat_number)
```

That means the database cannot store two claims for the same seat in the same show, even if two application threads reach the insert at nearly the same time.

This is the final durable protection against double booking. It must remain even if an application-level lock is added.

When an insert conflicts with an existing seat row, `ShowSeatRepository.claimSeat()` returns `false`. The booking service then rejects the complete booking request. The transaction rolls back all seat claims made by that request.

## 7. Application-Level Seat Locks

`BookingService` currently maintains an in-memory map of active seat locks:

```java
ConcurrentHashMap<String, Object> activeBookingLocks
```

Each selected seat becomes a lock key containing the show and seat, for example:

```text
544:A-1
544:B-3
```

The service tries to acquire all requested keys before entering the database transaction. If any key is already active, the request receives a conflict immediately.

The locks are removed in a `finally` block, so they are released after success, conflict, or failure.

This mechanism is useful because it reduces simultaneous work for the same seat inside one JVM. However, it is not a distributed lock. It does not coordinate separate Tomcat instances or separate application processes. The database primary key remains necessary.

## 8. All-or-Nothing Seat Claims

A request for multiple seats is treated as one unit:

```text
Request A1, B3
  -> A1 claimed
  -> B3 unavailable
  -> transaction rolls back A1
  -> request fails as a whole
```

The customer does not automatically receive only the seats that happened to remain available. The simulator refreshes the seat map and chooses a new complete selection when it handles a retryable conflict.

## 9. Seat Ordering and Deadlocks

When multiple rows or seats are processed, they should be normalized and handled in one consistent order, such as:

```text
row label ascending
seat number ascending
```

This reduces the chance of two transactions locking the same group in opposite orders:

```text
Bad:
Thread A: A1 -> B3
Thread B: B3 -> A1

Consistent:
Thread A: A1 -> B3
Thread B: A1 -> B3
```

Ordering prevents this class of circular wait, but it is not a complete guarantee against every database deadlock. Database constraints and transaction handling are still required.

## 10. Cache and Seat Availability

`SeatAvailabilityCache` is an in-memory performance layer. It stores occupied seats by show and distinguishes held and booked seats.

It helps by:

- Avoiding a database query for every seat-map refresh.
- Updating one seat at a time after a hold, confirmation, cancellation, or expiry.
- Tracking temporary hold expiration in memory.
- Running a cleanup task for expired cache entries.

The cache is not the source of truth. The database `show_seats` rows are authoritative. When the cache is missing or invalid, the service rebuilds it from the database.

Because the cache is local to one JVM, it cannot replace database protection in a multi-instance deployment.

## 11. Transactions and Rollback

`Database.inTransaction(...)` binds one JDBC connection to the current Java thread using `ThreadLocal`.

Within a transaction, booking changes such as these are committed together:

```text
Booking row
Seat claims
Wallet movements
Booking status
```

If a runtime error occurs, the transaction is rolled back. This prevents partial results such as a booking row without seats or a wallet debit without a confirmed booking.

## 12. Expiry Scheduler

`BookingExpiryScheduler` periodically calls `BookingService.expirePendingBookings()`.

Expired bookings include:

```text
PENDING
AWAITING_OTP
```

For each expired booking, the service:

1. Deletes its `show_seats` rows.
2. Invalidates active OTP challenges for the booking.
3. Changes the booking to `EXPIRED`.
4. Clears `expires_at`.
5. Removes the seats from the cache.

This releases seats abandoned during payment or OTP verification.

## 13. Simulation Concurrency Behavior

The simulator creates one independent HTTP session per customer. Each customer has:

- Its own cookies.
- Its own CSRF token.
- Its own wallet tracking values.
- Its own booking and cancellation state.

With many threads, several customers may read the same available seat map. This is expected. The race is resolved when the server attempts the durable seat claims.

Expected outcomes are:

```text
Seat claim succeeds:
  hold seats -> verify OTP -> pay -> confirm

Seat claim returns 409:
  wait -> refresh seats -> choose again -> retry

No available seats but held seats exist:
  wait -> refresh until timeout

Only booked seats remain:
  logout and finish without booking
```

The simulator should treat a seat conflict as a normal concurrency result, not as a server failure. A database deadlock is a separate transient database condition and should be handled separately from an ordinary seat conflict.

## 14. Main Code Areas

| Area | File | Responsibility |
|---|---|---|
| Booking HTTP resource | `backend/src/main/java/resource/BookingResource.java` | Booking lookup and legacy direct booking/cancel routes |
| OTP booking routes | `backend/src/main/java/resource/OtpResource.java` | Hold request, OTP verification, cancellation request and verification |
| Booking orchestration | `backend/src/main/java/service/BookingService.java` | Validation, transactions, holds, payment, confirmation, cancellation, expiry |
| Seat persistence | `backend/src/main/java/repository/ShowSeatRepository.java` | Durable seat claims and releases |
| Booking persistence | `backend/src/main/java/repository/BookingRepository.java` | Booking rows and expiry lookup |
| OTP persistence | `backend/src/main/java/repository/OtpChallengeRepository.java` | Challenge storage, consumption, and expiry invalidation |
| Payment | `backend/src/main/java/service/PaymentService.java` | Wallet debit, credit, and refund operations |
| Seat cache | `backend/src/main/java/cache/SeatAvailabilityCache.java` | Local seat availability cache and hold TTL cleanup |
| Database boundary | `backend/src/main/java/config/Database.java` | JDBC connections and transaction boundaries |
| Expiry scheduler | `backend/src/main/java/service/BookingExpiryScheduler.java` | Periodic expired-hold cleanup |
| Request security | `backend/src/main/java/filter/AuthFilter.java` | Session authentication and CSRF validation |
| Request identity | `backend/src/main/java/util/RequestUsers.java` | Reads the authenticated user and manages session tokens |
| Simulation worker | `CustomerSimulation.java` | One simulated customer's HTTP workflow and retries |
| Simulation coordinator | `Simulation.java` | Customer setup, thread pool, and result collection |

## 15. Practical Concurrency Summary

The application uses several layers together:

```text
HTTP session and CSRF
  -> identifies the customer

BookingService in-memory locks
  -> reduces same-JVM seat races

Database transaction
  -> groups booking, seats, and wallet changes

show_seats composite primary key
  -> final durable no-double-booking guard

SeatAvailabilityCache
  -> fast seat-map reads and local hold visibility

Expiry scheduler
  -> releases abandoned holds
```

No single mechanism is sufficient by itself. The cache is not a lock, the in-memory lock is not distributed, and the database key does not provide a friendly retry experience. Together they provide performance, correctness, cleanup, and observable concurrency behavior.