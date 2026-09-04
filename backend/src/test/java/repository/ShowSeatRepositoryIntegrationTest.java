package repository;

import config.Database;
import dto.request.BookingRequest;
import dto.request.SeatRequest;
import enums.BookingStatus;
import exception.ConflictException;
import exception.ValidationException;
import model.ShowSeat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import service.BookingService;
import service.PaymentService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShowSeatRepositoryIntegrationTest {

    private static Long adminId;
    private static Long customerId;
    private static Long theatreId;
    private static Long screenId;
    private static Long movieId;
    private static Long showId;
    private static Long bookingId;
    private static final String email = "seat-test-" + UUID.randomUUID() + "@example.com";

    @BeforeAll
    static void setUp() throws SQLException {
        Database.init();
        try (Connection connection = Database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                adminId = insertUser(connection, "Seat Test Admin", email + ".admin", "ADMIN");
                customerId = insertUser(connection, "Seat Test Customer", email + ".customer", "CUSTOMER");
                theatreId = insertTheatre(connection, adminId);
                screenId = insertScreen(connection, theatreId);
                movieId = insertMovie(connection);
                showId = insertShow(connection, movieId, screenId);
                bookingId = insertBooking(connection, adminId, showId);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    @AfterAll
    static void tearDown() throws SQLException {
        try (Connection connection = Database.openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement deleteSeats = connection.prepareStatement("DELETE FROM show_seats WHERE show_id = ?");
                 PreparedStatement deleteBookings = connection.prepareStatement("DELETE FROM bookings WHERE show_id = ?");
                 PreparedStatement deleteShows = connection.prepareStatement("DELETE FROM shows WHERE show_id = ?");
                 PreparedStatement deleteMovies = connection.prepareStatement("DELETE FROM movies WHERE movie_id = ?");
                 PreparedStatement deleteScreens = connection.prepareStatement("DELETE FROM screens WHERE screen_id = ?");
                 PreparedStatement deleteTheatres = connection.prepareStatement("DELETE FROM theatres WHERE theatre_id = ?");
                 PreparedStatement deleteUsers = connection.prepareStatement("DELETE FROM users WHERE id IN (?, ?)")) {
                setId(deleteSeats, showId);
                deleteSeats.executeUpdate();
                setId(deleteBookings, showId);
                deleteBookings.executeUpdate();
                setId(deleteShows, showId);
                deleteShows.executeUpdate();
                setId(deleteMovies, movieId);
                deleteMovies.executeUpdate();
                setId(deleteScreens, screenId);
                deleteScreens.executeUpdate();
                setId(deleteTheatres, theatreId);
                deleteTheatres.executeUpdate();
                deleteUsers.setLong(1, adminId);
                deleteUsers.setLong(2, customerId);
                deleteUsers.executeUpdate();
                connection.commit();
            }
        }
    }

    @Test
    void onlyOneConcurrentClaimSucceeds() throws Exception {
        ShowSeatRepository repository = new ShowSeatRepository();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> claim = () -> repository.claimSeat(seat("A", 1));
            List<Future<Boolean>> results = executor.invokeAll(List.of(claim, claim));

            assertTrue(results.get(0).get() ^ results.get(1).get());
            assertEquals(1, countClaimedSeats());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void onlyOneBookingServiceRequestAcquiresTheSameSeatLock() throws Exception {
        setWalletBalance(customerId, 1000);
        setWalletBalance(adminId, 0);

        BookingRequest request = new BookingRequest();
        request.setShowId(showId);
        SeatRequest requestedSeat = new SeatRequest();
        requestedSeat.setRowLabel("B");
        requestedSeat.setSeatNumber(5);
        request.setSeats(List.of(requestedSeat));

        BookingService bookingService = createBookingService();
        CyclicBarrier startTogether = new CyclicBarrier(2);
        AtomicReference<Long> winningBookingId = new AtomicReference<>();
        List<Long> durations = new CopyOnWriteArrayList<>();
        Callable<Boolean> attempt = () -> {
            startTogether.await(10, TimeUnit.SECONDS);
            long startedAt = System.nanoTime();
            try {
                Long createdBookingId = bookingService.bookTickets(customerId, request).getBookingId();
                winningBookingId.set(createdBookingId);
                return true;
            } catch (ConflictException expected) {
                return false;
            } finally {
                durations.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = executor.invokeAll(List.of(attempt, attempt));
            int successfulBookings = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    successfulBookings++;
                }
            }

            assertEquals(1, successfulBookings);
            assertEquals(1, countClaimedSeatsForRow("B", 5));
            assertNotNull(winningBookingId.get());
            assertTrue(durations.stream().anyMatch(duration -> duration < 5000),
                    "The rejected request should fail before payment delay completes");
        } finally {
            executor.shutdownNow();
            if (winningBookingId.get() != null) {
                bookingService.cancelBooking(winningBookingId.get(), customerId);
            }
        }

        assertEquals(0, countClaimedSeatsForRow("B", 5));
    }

        @Test
        void insufficientBalanceRollsBackBookingAndSeatClaim() throws Exception {
            setWalletBalance(customerId, 0);

        BookingRequest request = new BookingRequest();
        request.setShowId(showId);
        SeatRequest requestedSeat = new SeatRequest();
        requestedSeat.setRowLabel("B");
        requestedSeat.setSeatNumber(1);
        request.setSeats(List.of(requestedSeat));

        BookingService bookingService = new BookingService(
            new BookingRepository(),
            new UserRepository(),
            new ShowRepository(),
            new MovieRepository(),
            new ScreenRepository(),
            new TheatreRepository(),
            new ShowSeatRepository(),
            new PaymentService(new UserRepository()),
            new cache.SeatAvailabilityCache(),
            confirmation -> {
            });

        org.junit.jupiter.api.Assertions.assertThrows(
            ValidationException.class,
            () -> bookingService.bookTickets(customerId, request));

        assertEquals(0, countBookingsForCustomer());
        assertEquals(0, countClaimedSeatsForRow("B", 1));
        }

    @Test
    void expiredPendingBookingReleasesItsClaimedSeat() throws Exception {
        Long expiredBookingId = insertExpiredBooking();
        ShowSeatRepository seatRepository = new ShowSeatRepository();
        seatRepository.claimSeat(seat("B", 2, expiredBookingId));

        BookingService bookingService = createBookingService();
        bookingService.expirePendingBookings();

        assertEquals(BookingStatus.EXPIRED, new BookingRepository()
                .findById(expiredBookingId)
                .orElseThrow()
                .getStatus());
        assertEquals(0, countClaimedSeatsForRow("B", 2));
    }

    @Test
    void fullRefundMovesTheEntireRequestedAmountToCustomer() throws Exception {
        setWalletBalance(customerId, 0);
        setWalletBalance(adminId, 1000);

        new PaymentService(new UserRepository()).refundPayment(customerId, adminId, 1000);

        assertEquals(1000, walletBalance(customerId));
        assertEquals(0, walletBalance(adminId));
    }

    @Test
    void partialRefundLeavesTheRetainedAmountWithAdmin() throws Exception {
        setWalletBalance(customerId, 0);
        setWalletBalance(adminId, 1000);

        new PaymentService(new UserRepository()).refundPayment(customerId, adminId, 750);

        assertEquals(750, walletBalance(customerId));
        assertEquals(250, walletBalance(adminId));
    }

    @Test
    void cancellingConfirmedBookingReversesWalletsAndReleasesSeat() throws Exception {
        setWalletBalance(customerId, 0);
        setWalletBalance(adminId, 100);
        Long cancellableBookingId = insertBookingWithStatus("CONFIRMED", 100, showId);
        ShowSeatRepository seatRepository = new ShowSeatRepository();
        seatRepository.claimSeat(seat("B", 3, cancellableBookingId));

        createBookingService().cancelBooking(cancellableBookingId, customerId);

        assertEquals(BookingStatus.CANCELLED, new BookingRepository()
                .findById(cancellableBookingId)
                .orElseThrow()
                .getStatus());
        assertEquals(100, walletBalance(customerId));
        assertEquals(0, walletBalance(adminId));
        assertEquals(0, countClaimedSeatsForRow("B", 3));
    }

    @Test
    void cancellingAfterShowStartIsRejected() throws Exception {
        Long startedShowId = insertShowAt(LocalDateTime.now().minusHours(3));
        Long startedBookingId = insertBookingWithStatus("CONFIRMED", 100, startedShowId);
        ShowSeatRepository seatRepository = new ShowSeatRepository();
        ShowSeat startedSeat = seat("B", 4, startedBookingId);
        startedSeat.setShowId(startedShowId);
        seatRepository.claimSeat(startedSeat);

        try {
            org.junit.jupiter.api.Assertions.assertThrows(
                    ValidationException.class,
                    () -> createBookingService().cancelBooking(startedBookingId, customerId));
            assertEquals(BookingStatus.CONFIRMED, new BookingRepository()
                    .findById(startedBookingId)
                    .orElseThrow()
                    .getStatus());
        } finally {
            deleteShowTestData(startedShowId, startedBookingId);
        }
    }

    @Test
    void bookingAndPartialCancellationLeaveTwentyFivePercentWithAdmin() throws Exception {
        setWalletBalance(customerId, 1000);
        setWalletBalance(adminId, 0);
        Long nearTermShowId = insertShowAt(LocalDateTime.now().plusMinutes(10));

        BookingRequest request = new BookingRequest();
        request.setShowId(nearTermShowId);
        SeatRequest requestedSeat = new SeatRequest();
        requestedSeat.setRowLabel("B");
        requestedSeat.setSeatNumber(5);
        request.setSeats(List.of(requestedSeat));

        BookingService bookingService = createBookingService();
        Long createdBookingId = bookingService.bookTickets(customerId, request).getBookingId();
        assertEquals(900, walletBalance(customerId));
        assertEquals(100, walletBalance(adminId));

        try {
            bookingService.cancelBooking(createdBookingId, customerId);
            assertEquals(975, walletBalance(customerId));
            assertEquals(25, walletBalance(adminId));
            assertEquals(BookingStatus.CANCELLED, new BookingRepository()
                    .findById(createdBookingId)
                    .orElseThrow()
                    .getStatus());
        } finally {
            deleteShowTestData(nearTermShowId, createdBookingId);
        }
    }

    private ShowSeat seat(String row, int number) {
        return seat(row, number, bookingId);
    }

    private ShowSeat seat(String row, int number, Long currentBookingId) {
        ShowSeat seat = new ShowSeat();
        seat.setShowId(showId);
        seat.setRowLabel(row);
        seat.setSeatNumber(number);
        seat.setBookingId(currentBookingId);
        return seat;
    }

    private static Long insertExpiredBooking() throws SQLException {
        try (Connection connection = Database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO bookings (user_id, show_id, booking_time, status, total_amount, expires_at) VALUES (?, ?, NOW(), 'PENDING', 100, '2000-01-01 00:00:00')",
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, customerId);
            statement.setLong(2, showId);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private static Long insertBookingWithStatus(String status, int total, Long currentShowId) throws SQLException {
        try (Connection connection = Database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO bookings (user_id, show_id, booking_time, status, total_amount, expires_at) VALUES (?, ?, NOW(), ?, ?, NULL)",
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, customerId);
            statement.setLong(2, currentShowId);
            statement.setString(3, status);
            statement.setInt(4, total);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private static Long insertShowAt(LocalDateTime startTime) throws SQLException {
        try (Connection connection = Database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO shows (movie_id, screen_id, start_time) VALUES (?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, movieId);
            statement.setLong(2, screenId);
            statement.setTimestamp(3, java.sql.Timestamp.valueOf(startTime));
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private static void deleteShowTestData(Long currentShowId, Long currentBookingId) throws SQLException {
        try (Connection connection = Database.openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement deleteSeats = connection.prepareStatement("DELETE FROM show_seats WHERE show_id = ?");
                 PreparedStatement deleteBookings = connection.prepareStatement("DELETE FROM bookings WHERE booking_id = ?");
                 PreparedStatement deleteShows = connection.prepareStatement("DELETE FROM shows WHERE show_id = ?")) {
                setId(deleteSeats, currentShowId);
                deleteSeats.executeUpdate();
                setId(deleteBookings, currentBookingId);
                deleteBookings.executeUpdate();
                setId(deleteShows, currentShowId);
                deleteShows.executeUpdate();
                connection.commit();
            }
        }
    }

    private BookingService createBookingService() {
        return new BookingService(
                new BookingRepository(),
                new UserRepository(),
                new ShowRepository(),
                new MovieRepository(),
                new ScreenRepository(),
                new TheatreRepository(),
                new ShowSeatRepository(),
                new PaymentService(new UserRepository()),
                new cache.SeatAvailabilityCache(),
                confirmation -> {
                });
    }

    private static int countClaimedSeats() throws SQLException {
        try (Connection connection = Database.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM show_seats WHERE show_id = ?")) {
            statement.setLong(1, showId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static int countBookingsForCustomer() throws SQLException {
        try (Connection connection = Database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM bookings WHERE user_id = ? AND show_id = ?")) {
            statement.setLong(1, customerId);
            statement.setLong(2, showId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static int countClaimedSeatsForRow(String row, int number) throws SQLException {
        try (Connection connection = Database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM show_seats WHERE show_id = ? AND row_label = ? AND seat_number = ?")) {
            statement.setLong(1, showId);
            statement.setString(2, row);
            statement.setInt(3, number);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static void setWalletBalance(Long userId, int balance) throws SQLException {
        try (Connection connection = Database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE users SET wallet_balance = ? WHERE id = ?")) {
            statement.setInt(1, balance);
            statement.setLong(2, userId);
            statement.executeUpdate();
        }
    }

    private static int walletBalance(Long userId) throws SQLException {
        try (Connection connection = Database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT wallet_balance FROM users WHERE id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static Long insertUser(Connection connection, String name, String userEmail, String role) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO users (name, email, password_hash, role, wallet_balance) VALUES (?, ?, ?, ?, 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.setString(2, userEmail);
            statement.setString(3, "test-hash");
            statement.setString(4, role);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private static Long insertTheatre(Connection connection, Long ownerId) throws SQLException {
        return insertWithId(connection,
                "INSERT INTO theatres (admin_id, theatre_name, theatre_location) VALUES (?, 'Test Theatre', 'Test Location')",
                ownerId);
    }

    private static Long insertScreen(Connection connection, Long parentId) throws SQLException {
        return insertWithId(connection,
                "INSERT INTO screens (theatre_id, screen_name, row_range, seats_per_row) VALUES (?, 'Test Screen', 'A-B', 10)",
                parentId);
    }

    private static Long insertMovie(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO movies (movie_name, certification, description, director, duration_minutes, ticket_price) VALUES ('Test Movie', 'U', 'Test', 'Test', 120, 100)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private static Long insertShow(Connection connection, Long movie, Long screen) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO shows (movie_id, screen_id, start_time) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, movie);
            statement.setLong(2, screen);
            statement.setTimestamp(3, java.sql.Timestamp.valueOf(LocalDateTime.now().plusDays(2)));
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private static Long insertBooking(Connection connection, Long user, Long show) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO bookings (user_id, show_id, booking_time, status, total_amount, expires_at) VALUES (?, ?, NOW(), 'PENDING', 100, NOW())",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, user);
            statement.setLong(2, show);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private static Long insertWithId(Connection connection, String sql, Long parentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, parentId);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private static Long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            keys.next();
            return keys.getLong(1);
        }
    }

    private static void setId(PreparedStatement statement, Long id) throws SQLException {
        statement.setLong(1, id);
    }
}