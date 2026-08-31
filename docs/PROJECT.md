# Movie Ticket Booking System — Project Plan & Specification

This document describes **what this project is**, **what it is not**, and **how it is planned**. It distinguishes **final decisions** from implementation details that can still be tuned during development.

---

## 1. Project objective

Build a movie ticket booking system with two user types:

- **Admin** — owns theatres and manages screens and shows
- **Customer** — browses movies, books seats, pays, and cancels bookings

The system manages this hierarchy:

```text
Admins
  ↓
Theatres
  ↓
Screens
  ↓
Shows
  ↓
Movies

Customers
  ↓
Browse movies
  ↓
Find shows
  ↓
View seats
  ↓
Book seats
  ↓
Pay
  ↓
Receive booking
  ↓
Cancel booking
```

The application is split into:

```text
Movie-ticket-booking-system/
│
├── Frontend/
│
└── Backend/
```

The backend follows a layered architecture:

```text
Controller
    ↓
Service
    ↓
Repository
    ↓
Database
```

Layer responsibilities:

| Layer | Responsibility |
| --- | --- |
| **Controllers** | Handle requests and responses |
| **Services** | Business logic and orchestration |
| **Repositories** | Database access |
| **Models** | Domain entities |
| **DTOs** | Data entering or leaving the application |
| **Enums** | Constrained states and types |

---

## 2. Business scope

### Admin capabilities

An admin can:

1. Create and manage theatres they own
2. Create and manage screens inside those theatres
3. Create and manage shows on those screens
4. Associate movies with shows
5. Therefore control which movies play in their theatres

An admin does **not** own a movie. Movies are **global** entities.

The ownership/authorization chain is:

```text
Theatre
   ↓
Screen
   ↓
Show
   ↓
Movie
```

Admin authorization is established through the theatre → screen → show chain, not by attaching an admin directly to a movie.

### Customer capabilities

A customer can:

1. Browse movies
2. View movie details
3. View theatres showing a movie
4. View shows for a movie
5. View available seats for a show
6. Select seats
7. Book seats
8. Make a **simulated** payment
9. View their bookings
10. Cancel a confirmed booking

There is **no permanent "seat selection" state**. Selecting a seat on the frontend does **not** reserve it. A seat is claimed only when the customer starts the booking transaction.

---

## 3. Intentional simplifications

The project deliberately avoids extra complexity.

### No movie genres

Movies do not have a genre table or genre relationship.

### No special show pricing

Ticket price is associated with the **movie**. There is no VIP fare, weekend fare, show-specific fare, or dynamic pricing.

```text
Movie.ticketPrice
```

is the price per ticket.

### No `SeatStatus`

We do not maintain `AVAILABLE` / `HELD` / `BOOKED` on a permanent seat table. Seat state is **derived** from the existence and state of a `show_seat` record.

### No `booking_seat` table

The design uses:

```text
show_seat → booking
```

instead of:

```text
booking → booking_seat → seat
```

This is an intentional simplification.

---

## 4. Database design

Core entities:

```text
users
theatres
screens
movies
shows
bookings
show_seats
```

Physical seats are **not** stored as one row per chair. Screen layout is stored as a compact definition on the screen (see §9–§11).

---

## 5. Users

```text
users
----------------------------
user_id          PK
name
email
password
role
wallet_balance
```

`role` is:

```text
CUSTOMER
ADMIN
```

There is **one users table**, not separate `admins` and `customers` tables. Admins and customers share identity attributes; they differ in **authorization and behavior**.

### Role enum

```java
public enum Role {
    CUSTOMER,
    ADMIN
}
```

---

## 6. Theatres

```text
theatres
----------------------------
theatre_id       PK
admin_id         FK → users.user_id
theatre_name
theatre_location
```

```text
Admin 1 ─────── * Theatre
```

An admin can own multiple theatres. A theatre belongs to one admin. This relationship is the root of admin authorization.

---

## 7. Screens

A screen belongs to a theatre.

```text
screens
----------------------------
screen_id        PK
theatre_id       FK → theatres.theatre_id
screen_name
row_range
seats_per_row
```

Example:

```text
Screen 1
row_range = "A-J"
seats_per_row = 20
```

Means:

```text
A1 A2 ... A20
B1 B2 ... B20
...
J1 J2 ... J20
```

Total: `10 × 20 = 200` seats.

---

## 8. Why we don't store every physical seat

An initial design could persist every seat:

```text
seats
-------------------
seat_id
screen_id
row_label
seat_number
```

For a 200-seat screen that is 200 rows of structural data we do not need.

Instead, the screen stores its **layout definition**:

```text
row_range = A-J
seats_per_row = 20
```

The application derives seat coordinates:

```text
Screen definition
      ↓
A-J × 1-20
      ↓
200 possible seats
```

This is intentional compression of structural information. It is appropriate because layouts are simple and uniform.

---

## 9. Screen layout limitation

The current layout assumes:

- sequential alphabetic rows
- the same number of seats in every row
- no gaps
- no irregular arrangements

`A-J` with 20 seats each works. This model **cannot** naturally express:

```text
A: 10 seats
B: 12 seats
C: 8 seats
```

or:

```text
A1 A2 A3     A7 A8
```

That complexity is **out of scope**.

---

## 10. Movies

```text
movies
----------------------------
movie_id
movie_name
movie_certification
movie_description
movie_director
duration_in_minutes
ticket_price
```

A movie is global. It is not owned by a theatre. Association happens through a show:

```text
Movie
  ↑
Show
  ↓
Screen
  ↓
Theatre
```

---

## 11. Shows

```text
shows
----------------------------
show_id
movie_id       FK → movies.movie_id
screen_id      FK → screens.screen_id
show_timing
```

A show answers: **which movie is playing on which screen, and when?**

```text
Show
 ├── movie_id
 ├── screen_id
 └── show_timing
```

A show belongs indirectly to a theatre through its screen.

### Show creation

When an admin creates a show:

```text
Admin
 ↓
select screen
 ↓
select movie
 ↓
provide show timing
```

The backend must verify:

```text
Movie exists
AND
Screen exists
AND
Screen → Theatre exists
AND
Theatre belongs to requesting Admin
AND
show timing is valid
```

Only then: `INSERT show`.

`ShowService` is responsible for attaching a movie to a show.

---

## 12. Seats and `show_seat`

Physical seats come from the screen layout. Every show needs **independent** seat state.

Two shows on the same screen must not share occupancy:

```text
Show 101 → A1 booked
Show 102 → A1 available
```

Seat state is scoped to `show + seat`.

---

## 13. Sparse `show_seat` design

We do **not** insert every seat for every show (200 rows × N shows). We use a sparse table:

```text
show_seats
----------------------------
show_id
row_label
seat_number
booking_id
```

Primary key:

```text
(show_id, row_label, seat_number)
```

Only **claimed** seats are inserted.

| Condition | Derived meaning |
| --- | --- |
| No `show_seat` row | **AVAILABLE** |
| Row exists, booking `PENDING` | **HELD** |
| Row exists, booking `CONFIRMED` | **BOOKED** |

This is the main seat-storage optimization.

---

## 14. Why `booking_id` instead of `user_id` on `show_seat`

We store `show_seat.booking_id`, not `show_seat.user_id`.

A booking already identifies:

- customer (`user_id`)
- show
- booking state
- transaction / amount

So:

```text
show_seat → booking → user
```

is enough. We do not duplicate `user_id` on `show_seat`.

---

## 15. Bookings

```text
bookings
----------------------------
booking_id
user_id
show_id
booking_status
total_amount
booking_time
expires_at
```

`show_id` stays on `bookings`. Deriving it only from `show_seat` was considered and rejected: a booking fundamentally belongs to a show, and `getBooking()` / `getUserBookings()` stay simpler.

### Booking status

```java
public enum BookingStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}
```

| Status | Meaning |
| --- | --- |
| **PENDING** | Booking started; payment/confirmation not complete |
| **CONFIRMED** | Payment succeeded; seats are booked |
| **CANCELLED** | Customer cancelled a confirmed booking |
| **EXPIRED** | A pending booking exceeded its lifetime |

---

## 16. No `SeatStatus` enum

We do not have `SeatStatus.AVAILABLE` / `HELD` / `BOOKED` as persisted values.

For `show_id + row + seat`:

1. If no `show_seat` record exists → **AVAILABLE**
2. If a record exists, inspect its booking:
   - `PENDING` → **HELD**
   - `CONFIRMED` → **BOOKED**

---

## 17. Customer seat viewing

`getAvailableSeats(showId)`:

1. Get the show
2. Get its screen
3. Read the screen layout
4. Generate all possible seat coordinates
5. Query `show_seat` for claimed seats
6. Mark those seats unavailable
7. Return the representation

Example: screen `A-C`, 5 seats per row. Possible seats:

```text
A1 A2 A3 A4 A5
B1 B2 B3 B4 B5
C1 C2 C3 C4 C5
```

If `show_seat` contains `A2` and `C4`, those are BOOKED/HELD; the rest are AVAILABLE.

---

## 18. Booking flow

The booking operation is the core workflow.

Customer sends `showId` and selected seats, e.g. show `101`, seats `A1`, `A2`, `A3`.

Backend steps:

1. **Validate customer** — user exists and is a customer
2. **Get show**
3. **Get screen** via `Show.screen_id`
4. **Get movie** via `Show.movie_id`
5. **Get theatre** via `Screen.theatre_id`
6. **Determine admin** via `Theatre.admin_id` (for payment credit)
7. **Validate selected seats** — row exists, seat number in range, no duplicates in the request
8. **Calculate price** — `movie.ticket_price × number of seats`  
   Example: ₹200 × 3 = ₹600

---

## 19. Booking transaction

Everything from booking creation through payment confirmation must happen in a **single database transaction**.

```text
BEGIN TRANSACTION

Create PENDING booking

Claim A1
Claim A2
Claim A3

Process payment

Set booking CONFIRMED

COMMIT
```

If anything fails: `ROLLBACK`.

This prevents:

- money deducted but booking failed
- two seats claimed but the third insert failed

---

## 20. Concurrency

If two customers try to book `A1` at the same time, Java-level `if (available) { insert(); }` is **not** sufficient. Both transactions can see “no row” and both try to insert.

The database enforces uniqueness:

```text
PRIMARY KEY (show_id, row_label, seat_number)
```

Seat claim must be atomic: attempt insert; if already claimed, fail.

**We do not use Java `synchronized`.** Multiple app servers would not share JVM locks. The correct boundary is:

```text
Application logic
        +
Database transaction
        +
Database uniqueness
```

The database is the final authority for concurrency.

---

## 21. Payment

Payment is **simulated**. There is no Stripe, Razorpay, PayPal, or UPI.

The system uses wallet balances.

Example:

- Customer wallet: ₹1000
- Tickets: ₹600
- After payment: customer ₹400, theatre admin += ₹600

`PaymentService`:

1. Get customer
2. Get theatre admin
3. Check customer balance
4. Debit customer
5. Credit admin

Payment **must** participate in the same transaction as booking.

Invariant:

```text
CONFIRMED booking  ↔  successful payment
```

Never debit without a successful booking, and never confirm a booking without payment.

---

## 22. Cancellation

Customer can cancel a **confirmed** booking:

```text
Customer
 ↓
BookingService
 ↓
verify booking belongs to customer
 ↓
verify status = CONFIRMED
 ↓
determine theatre admin
 ↓
refund customer
 ↓
reverse admin credit
 ↓
delete show_seat rows
 ↓
set booking = CANCELLED
```

After cancellation, `show_seat` rows for that booking are deleted, so those seats become available again.

---

## 23. Sparse seat lifecycle

```text
                SELECTED
                   │
                   │
              not persisted
                   │
                   ↓
             BOOKING STARTS
                   │
                   ↓
          INSERT show_seat
                   │
                   ↓
                PENDING
                   │
          ┌────────┴────────┐
          │                 │
       payment            timeout
       succeeds              │
          │                  ↓
          ↓               EXPIRED
      CONFIRMED               │
          │                  ↓
          │             DELETE row
          │
       cancel
          │
          ↓
      CANCELLED
          │
          ↓
    DELETE show_seat
```

---

## 24. Expiry

A pending booking cannot live forever. Each booking has `expires_at`.

A scheduled backend task periodically finds:

```text
status = PENDING
AND
expires_at < current time
```

and:

- sets booking to **EXPIRED**
- deletes related `show_seat` rows

The scheduler interval is an implementation tuning decision.

---

## 25. Domain model (UML)

The domain model represents **business entities and relationships**, not a 1:1 copy of tables.

Major classes:

```text
User
Theatre
Screen
Movie
Show
Booking
ShowSeat
```

Relationships:

```text
User 1 ─── * Theatre
Theatre 1 ─── * Screen
Screen 1 ─── * Show
Movie 1 ─── * Show
Show 1 ─── * ShowSeat
User 1 ─── * Booking
Booking 1 ─── * ShowSeat
```

Narrative:

```text
User
 │
 ├──── owns ──────── Theatre
 │                    │
 │                    └──── contains ─── Screen
 │                                         │
 │                                         └──── hosts ─── Show
 │                                                        │
 │                                                        ├── Movie
 │                                                        │
 │                                                        └── ShowSeat
 │
 └──── creates ───── Booking
                       │
                       └──── claims ─── ShowSeat
```

The domain model does **not** include repository classes. Repositories are infrastructure.

---

## 26. Application architecture (LLD)

```text
                    ┌──────────────┐
                    │   Frontend   │
                    └──────┬───────┘
                           HTTP
                            ↓
                    ┌──────────────┐
                    │ Controllers  │
                    └──────┬───────┘
                           ↓
                    ┌──────────────┐
                    │  Services    │
                    └──────┬───────┘
                           ↓
                    ┌──────────────┐
                    │ Repositories │
                    └──────┬───────┘
                           ↓
                    ┌──────────────┐
                    │  Database    │
                    └──────────────┘
```

Three related but **different** views:

| View | Concern |
| --- | --- |
| **Database design** | Tables, PKs, FKs, constraints, indexes, transactions |
| **Domain model** | Entities, relationships, business concepts |
| **LLD / application design** | Controllers, services, repositories, DTOs, workflows, concurrency |

Do not treat a UML class diagram as a copy of the schema.

---

## 27. Controllers

Conceptual controllers:

```text
MovieController
TheatreController
ScreenController
ShowController
BookingController
```

Related customer operations stay together. For example, `MovieController` can handle `browseMovies()`, `getMovieDetails()`, and `getTheatresShowingMovie()` because they are one browsing workflow.

Show/seat viewing goes through the show-facing API. There is no extra controller just because a “Seat” concept exists.

Payment does **not** get its own controller; payment is part of booking.

---

## 28. Services

```text
MovieService
TheatreService
ScreenService
ShowService
PaymentService
BookingService
```

| Service | Responsibilities |
| --- | --- |
| **MovieService** | `browseMovies()`, `getMovieDetails()`, `getTheatresShowingMovie()`, `createMovie()`, `updateMovie()`, `deleteMovie()` |
| **TheatreService** | `createTheatre()`, `updateTheatre()`, `deleteTheatre()`, ownership validation |
| **ScreenService** | `createScreen()`, `updateScreen()`, `deleteScreen()`, verify Screen → Theatre → Admin |
| **ShowService** | `createShow()`, `updateShow()`, `deleteShow()`, `getShowsForMovie()`, `getShowsForScreen()`, validate movie/screen/theatre/admin/timing |
| **PaymentService** | `processPayment()`, `refundPayment()` — wallet movement only; does not create bookings |
| **BookingService** | `bookTickets()`, `getBooking()`, `getMyBookings()`, `cancelBooking()`, `getAvailableSeats()` — orchestrates booking |

`BookingService` is the most coordinated service.

---

## 29. Repositories

Repositories are **concrete classes**. We do not introduce repository interfaces unless we need polymorphic implementations.

```text
UserRepository
TheatreRepository
ScreenRepository
MovieRepository
ShowRepository
BookingRepository
ShowSeatRepository
```

Repositories execute queries and persist/retrieve/update/delete entities. Business rules stay in services.

Without repositories, services would mix business logic with SQL. Instead:

```text
BookingService
   ↓
BookingRepository
   ↓
Database
```

The service says “I need this booking.” The repository decides how to retrieve it.

---

## 30. DTOs vs models

**DTO** = Data Transfer Object — data across application boundaries.

- `BookingRequest` — inbound booking payload (`showId`, seats)
- `BookingResponse` — outbound booking representation

We do not expose internal `Booking` models directly on the HTTP API. That keeps the API from being tightly coupled to the database/domain shape.

**Models** represent what exists in the domain (`User`, `Theatre`, `Screen`, `Movie`, `Show`, `Booking`, `ShowSeat`). They hold attributes and accessors.

---

## 31. Validation split

| Layer | Validation |
| --- | --- |
| **Controller** | Parse HTTP request, call service |
| **Service** | Movie exists? Admin owns theatre? Seat valid? Booking cancellable? Wallet sufficient? |
| **Database** | PK, FK, UNIQUE, NOT NULL |

The database remains the final integrity boundary.

---

## 32. Admin authorization chain

This is a critical invariant.

If admin `10` wants to modify screen `500`, we do **not** trust a `screen.adminId` field — screens do not have one.

```text
screen 500
   ↓
theatre_id
   ↓
theatre
   ↓
admin_id
   ↓
compare with logged-in admin
```

Same for shows:

```text
Show → Screen → Theatre → Admin
```

---

## 33. Service dependencies

```text
MovieService
 ├── MovieRepository
 └── TheatreRepository

TheatreService
 └── TheatreRepository

ScreenService
 ├── ScreenRepository
 └── TheatreRepository

ShowService
 ├── ShowRepository
 ├── MovieRepository
 ├── ScreenRepository
 └── TheatreRepository

PaymentService
 └── UserRepository

BookingService
 ├── BookingRepository
 ├── UserRepository
 ├── ShowRepository
 ├── MovieRepository
 ├── ScreenRepository
 ├── TheatreRepository
 ├── ShowSeatRepository
 └── PaymentService
```

---

## 34. Data flows

### Browse movies

```text
Customer
 ↓
MovieController
 ↓
MovieService
 ↓
MovieRepository
 ↓
Database
 ↓
Movies
 ↓
MovieService
 ↓
MovieResponse
 ↓
Customer
```

### Find theatres / shows

```text
Customer
 ↓
MovieController
 ↓
MovieService
 ↓
MovieRepository
 ↓
validate movie
 ↓
TheatreRepository
 ↓
Theatres showing the movie
```

Then:

```text
ShowController
 ↓
ShowService
 ↓
ShowRepository
 ↓
Shows for movie
```

### View seats

```text
Customer
 ↓
ShowController
 ↓
BookingService / seat-query operation
 ↓
ShowRepository
 ↓
ScreenRepository
 ↓
ShowSeatRepository
 ↓
derive complete seat layout
 ↓
mark claimed seats
 ↓
SeatResponse[]
```

Optimization: **Screen defines all possible seats; ShowSeat stores only claimed seats.**

### Booking

```text
Customer
      ↓
BookingController
      ↓
BookingService
      │
      ├── UserRepository
      ├── ShowRepository
      ├── ScreenRepository
      ├── MovieRepository
      ├── TheatreRepository
      │
      ├── create PENDING Booking
      │
      ├── claim ShowSeats
      │
      ├── PaymentService
      │      └── Wallet updates
      │
      └── Booking → CONFIRMED
             ↓
           COMMIT
```

### Cancellation

```text
Customer
 ↓
BookingController
 ↓
BookingService
 ↓
get Booking
 ↓
verify ownership
 ↓
verify CONFIRMED
 ↓
resolve Theatre/Admin
 ↓
PaymentService.refundPayment()
 ↓
delete show_seats
 ↓
Booking → CANCELLED
 ↓
COMMIT
```

---

## 35. Critical invariants

These must be preserved.

1. A screen belongs to exactly one theatre.
2. A show belongs to exactly one screen and one movie.
3. A show therefore indirectly belongs to one theatre.
4. An admin can manage only their theatres and the screens/shows beneath them.
5. A movie is globally defined and does not belong to a theatre.
6. Ticket price comes from the movie.
7. A seat is uniquely identified within a show by `(show_id, row_label, seat_number)`.
8. A missing `show_seat` row means the seat is available.
9. A confirmed booking must have successfully completed payment.
10. A cancelled booking releases its `show_seat` records.
11. A booking belongs to exactly one user and one show.
12. All booking / payment / seat-claim operations must be transactionally consistent.

---

## 36. Explicitly out of scope

Do **not** add these unless requirements are expanded:

- Movie genres, actors, languages
- Multiple ticket pricing tiers, VIP seats, dynamic pricing, seat-specific pricing
- Real payment gateways
- Coupons, discounts, taxes
- Food ordering
- Reviews, ratings
- Subscriptions, loyalty programs
- Multiple booking payment methods
- Complex theatre layouts

Also do **not** introduce:

- `SeatStatus` table
- `BookingSeat` table
- `ScreenLayout` table
- separate `Admin` / `Customer` tables

---

## 37. Recommended backend package structure

```text
Backend/
│
├── model/
│   ├── User.java
│   ├── Theatre.java
│   ├── Screen.java
│   ├── Movie.java
│   ├── Show.java
│   ├── Booking.java
│   └── ShowSeat.java
│
├── dto/
│   ├── request/
│   │   ├── MovieRequest.java
│   │   ├── TheatreRequest.java
│   │   ├── ScreenRequest.java
│   │   ├── ShowRequest.java
│   │   ├── BookingRequest.java
│   │   └── SeatRequest.java
│   │
│   └── response/
│       ├── MovieResponse.java
│       ├── MovieDetailsResponse.java
│       ├── TheatreResponse.java
│       ├── ShowResponse.java
│       ├── SeatResponse.java
│       └── BookingResponse.java
│
├── controller/
│   ├── MovieController.java
│   ├── TheatreController.java
│   ├── ScreenController.java
│   ├── ShowController.java
│   └── BookingController.java
│
├── service/
│   ├── MovieService.java
│   ├── TheatreService.java
│   ├── ScreenService.java
│   ├── ShowService.java
│   ├── PaymentService.java
│   └── BookingService.java
│
├── repository/
│   ├── UserRepository.java
│   ├── MovieRepository.java
│   ├── TheatreRepository.java
│   ├── ScreenRepository.java
│   ├── ShowRepository.java
│   ├── BookingRepository.java
│   └── ShowSeatRepository.java
│
├── enums/
│   ├── Role.java
│   └── BookingStatus.java
│
└── exception/
```

---

## 38. Final mental model

Four layers:

```text
                 USER
                  │
                  ▼
          ┌───────────────┐
          │  CONTROLLER   │
          │ "What request?"│
          └───────┬───────┘
                  │
                  ▼
          ┌───────────────┐
          │    SERVICE    │
          │ "What should  │
          │  happen?"     │
          └───────┬───────┘
                  │
                  ▼
          ┌───────────────┐
          │  REPOSITORY   │
          │ "How do I get │
          │  /save data?" │
          └───────┬───────┘
                  │
                  ▼
             DATABASE
```

Business domain:

```text
             ADMIN
               │
               ▼
            THEATRE
               │
               ▼
             SCREEN
               │
               ▼
              SHOW ────────── MOVIE
               │
               ▼
          SHOW_SEAT
               │
               ▼
            BOOKING
               │
               ▼
              USER
```

Payment during booking:

```text
BookingService
      │
      ├── claim seats
      ├── calculate price
      ├── PaymentService
      │       ├── debit customer
      │       └── credit theatre admin
      │
      └── confirm booking
```

**Central design principle:** the database stores persistent facts; the service layer coordinates the business process.

The main technical choices are:

1. **Sparse `show_seat`** — only claimed seats are stored
2. **Database uniqueness** — `(show_id, row_label, seat_number)` is the concurrency boundary
3. **Single transaction** — booking, seat claims, and wallet payment succeed or roll back together
