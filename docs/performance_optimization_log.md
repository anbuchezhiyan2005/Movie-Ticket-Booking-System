# Performance Optimization Log

A record of performance problems found during the booking-flow review, how they
hurt when the app grows, the trade-offs looked at, and the final decision.
Update this file whenever a new optimization is made or an old one is rolled back.

---

## 1. Combine the show + screen + movie + theatre reads into one query

### 1.1 What I spotted

In `BookingService`, three methods (`holdTicketsInternal`,
`bookTicketsInternal`, `confirmHeldBookingInternal`) each loaded the same set
of related data using four separate single-row queries:

```
Show    -> getShow(showId)
Screen  -> getScreen(show.getScreenId())
Movie   -> getMovie(show.getMovieId())
Theatre -> getTheatre(screen.getTheatreId())
```

None of these queries is slow by itself (they all look up by primary key), but
every booking request fired all four back to back.

### 1.2 Impact when the app scales

Every booking request does 4 round trips to the database instead of 1.

- The booking flow is the busiest path in the app, especially during the load
  test where many customers book at the same time.
- The connection pool is capped at 10 connections. Keeping a connection busy
  4× longer per request means the pool can run dry faster when many people
  book together.
- These four values (show, screen, movie, theatre) are always needed together,
  so fetching them separately never saves anything — it just adds overhead.

### 1.3 Solution I suggested

Fetch all four records together in one query using a join
(`shows ⨝ screens ⨝ movies ⨝ theatres`). I added a new method
`ShowRepository.findWithDetails(showId)` that returns all four in one
`ShowWithDetails(show, screen, movie, theatre)` object. The booking flow now
needs only one database trip.

### 1.4 Trade-offs I weighed

| Option | Good things | Bad things |
|---|---|---|
| Keep the 4 separate queries | Simple, each query does one thing | 4 reads on the busiest path; gets slower as traffic grows |
| Join everything everywhere | Fewest reads overall | Repos become tied to specific screens; you fetch data someone doesn't need; harder to read and test |
| **Join only on the hot path** | Cuts 4 reads to 1 where it matters most; the older simple queries stay for other less-busy places | Adds one more method and a small data object just for the booking flow |

Database reads become expensive once traffic goes up — the cost is mostly the
round trip, not the lookup itself. So reducing reads on the busiest path is
worth it. The extra code (one method, one record, one join) is small and stays
contained, so it was an easy trade to accept.

### 1.5 Final decision

Added `ShowRepository.findWithDetails` and switched the three booking-flow
methods to use it (4 reads → 1 read). The old `findById` getters still exist
for `getAvailableSeats` and `cancelInternal` because those paths aren't busy
yet. If profiling later shows another path getting hot, we add a similar join
there — no need to build it before it's needed.

While doing this, a latent bug showed up: `mapShow` expected columns named
`show_movie_id` / `show_screen_id`, but the plain `findById`,
`findByMovieId`, and `findByScreenId` queries didn't alias their columns, so
any call to those getters broke with "Column 'show_movie_id' not found". Fixed
by aliasing `movie_id AS show_movie_id` and `screen_id AS show_screen_id` in
the three plain SELECTs, so the shared `mapShow` helper is correct everywhere.

Verified with `mvn test`: 33 tests, 0 failures.

---

## 2. Merge the two seat-validation helper methods into one

### 2.1 What I spotted

`validateSeats(seats, screen)` and `validateDuplicateSeats(seats)` were two
separate methods, each looping through the seat list:

- One checks that the row and seat number are within the screen's layout and
  normalizes the row label.
- The other checks that the same seat wasn't picked twice.

### 2.2 Impact when the app scales

Every seat got visited twice for every hold request — once per validation. The
work per seat is tiny, but it doubles the work on a method that runs on every
booking, and there are two places where the seat rules live, so they can drift
out of sync.

### 2.3 Solution I suggested

Put both checks in one `validateSeats(seats, screen)` method — bounds check,
label normalization, and duplicate detection in a single loop — and delete
`validateDuplicateSeats`.

### 2.4 Trade-offs I weighed

| Option | Good things | Bad things |
|---|---|---|
| Keep two helpers | Each one does one clear thing | Two loops over the same list; every call site must remember to call both |
| Merge into one helper | One loop, one call site, one place where seat rules live | One method does two things (bounds + duplicates), so it's a bit denser |

This one was easy: the two checks run on the same data at the same point in
the flow, and a duplicate can only be spotted after the row label is
normalized. So combining them is natural, not forced. All seat rules now sit
in one method.

### 2.5 Final decision

Merged the two validations into a single `validateSeats` method and removed
`validateDuplicateSeats` and its two call sites. Behaviour is unchanged — same
error messages, same order (bounds first, then duplicates).

---

## 3. Normalize the seat list once instead of twice per request

### 3.1 What I spotted

`holdTickets` sorted and normalized the requested seats twice per request:
once inside `bookingLockKeys` (to build the lock keys) and again inside
`holdTicketsInternal` (to claim and validate the seats). Sorting is cheap, but
doing the same work twice on the busiest path is needless, and it splits the
"what does the seat list look like" logic into two places that can drift.

### 3.2 Solution and decision

Normalize once in `holdTickets`, pass the resulting list into
`holdTicketsInternal`, and have `bookingLockKeys` take the already-normalized
list instead of re-deriving it. One place owns the seat-ordering rule.

### 3.3 Trade-offs I weighed

| Option | Good things | Bad things |
|---|---|---|
| Keep normalizing twice | Two methods stay independent | Double work per request; two copies of the sorting rule |
| Compute once, pass down | One pass, one place owns the rule | Internal method takes one extra parameter |

Easy call: the list is the same logical data in both places, so threading it
through removes real duplicate work for almost no cost.

---

## 4. Removed the test-only booking methods and dead `/bookings` endpoints

### 4.1 What I spotted

`BookingService.bookTickets`, `bookTicketsInternal`, and `cancelBooking`
(non-OTP) had no production callers — the frontend and `OtpResource` use the
OTP flow, and `BookingResource.create`/`cancel` were throw-only placeholders.
`bookTickets` also carried a 2-second `PAYMENT_DELAY_MILLIS` sleep — a soak
test shim sitting in the live code path.

### 4.2 Solution and decision

Deleted the three test-only methods, the `PAYMENT_DELAY_MILLIS` sleep, and the
`BookingResource` stub endpoints. Rewrote the repository integration tests to
drive the real OTP flow (`holdTickets` + `confirmHeldBookingWithOtp` +
`cancelBookingWithOtp` + `expireBookingHold`), and deleted the
`insufficientBalanceRollsBackBookingAndSeatClaim` test — it tested the
removed `bookTickets` behavior, which the OTP flow deliberately doesn't have
(a payment failure leaves the hold intact for retry).
Also removed `getMovie` and the `movieRepository` field, which became orphaned
once `getBooking` stopped loading a movie by hand.

Verified with `mvn test`: drops the obsolete  sleep and ~170 lines of dead
code; 33 tests, 0 failures.

---

## 5. Fixed the false "seat is no longer available" notice during OTP booking

### 5.1 What I spotted

After clicking "Book Selected Seats", the seat grid showed the chosen seat as
`HELD` (yellow), but within ~0.5-1s the client also flashed a red
"Seat A-1 is no longer available" notice — while the booking was actually
succeeding and the OTP email was on its way.

### 5.2 Root cause

A time-of-check race between the two client calls:

1. `POST /bookings/otp/request` runs. The server holds the seats, commits the
   DB transaction, and flips the availability cache to `HELD` *inside*
   `holdTickets` (`BookingService.java:155`) — **before** the OTP email is sent
   and **before** the HTTP response returns.
2. The OTP email is sent synchronously (`Transport.send`, ~0.5-1s real SMTP).
3. The response finally returns, and the client learns which seats are its own
   via `storeBookingChallenge` → `bookingOtp`.

So between step 1 and step 3 there is a 0.5-1s window where the cache says
`HELD` but the client doesn't yet know those seats belong to it. If the 5s
`startSeatRefresh` poll lands in that window, `applyOccupiedSeats` sees a
selected seat turning `HELD`, concludes it was lost, and fires the false
notice. The server never errors; the booking proceeds fine.

The first fix (treat `bookingOtp.booking.seats` as own) only suppressed the
notice *after* the response arrived — too late, because the poll already fired
during the SMTP window.

### 5.3 Solution I suggested

Claim the seats optimistically on the client at the moment the booking is
submitted. The client already knows exactly which seats it is about to book
(`selectedSeats`), so set `pendingHoldSeats = new Set(selectedSeats)` before
the POST leaves (`app.js:559`). `applyOccupiedSeats` now treats any seat in
`pendingHoldSeats ∪ bookingOtp.booking.seats` as its own: it renders as `HELD`
and stays selected, but is never "removed" as lost.

`pendingHoldSeats` is cleared when the OTP response lands (the confirmed
`bookingOtp` becomes authoritative) and on any request failure (a real 409
still surfaces the genuine conflict error via `showNotice`).

### 5.4 Trade-offs I weighed

| Option | Good things | Bad things |
|---|---|---|
| Backend fix: return which seats the session holds | Client has no guessing | Bigger change; seat-map payload would need session-aware ownership info |
| Client fix: mark own seats optimistically at click | Zero backend change; seats are known exactly at click time; genuine 409s still shown | Requires a small global on the client; needs both in-flight and confirmed signals |

The client already carries all the information needed, and the optimistic mark
needs no hint from the server. A genuine conflict is still reported because
the failing `otp/request` throws, clears the pending set, and shows the real
error.

### 5.5 Final decision

Added `pendingHoldSeats` in `app.js` and union it with
`bookingOtp.booking.seats` inside `applyOccupiedSeats`. Backend untouched;
verified manually in real SMTP mode (the window is ~instant in
`OTP_DELIVERY_MODE=SIMULATION`, which is why it slipped past earlier manual
checks).

---

## Deliberate shortcuts / things left for later

- The booking flow and `getBooking` now use the join. `getAvailableSeats` and
  `cancelInternal` still call the single-row getters (`getShow` +
  `getScreen` + `getTheatre` — 3 reads). Revisit them if profiling shows
  either path getting busy.