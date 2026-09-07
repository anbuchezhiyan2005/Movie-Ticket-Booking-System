package service;

import config.Database;
import dto.request.BookingRequest;
import dto.request.SeatRequest;
import dto.response.BookingResponse;
import enums.BookingStatus;
import enums.OtpPurpose;
import exception.ValidationException;
import model.Booking;
import model.OtpChallenge;
import model.ShowSeat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import repository.BookingRepository;
import repository.OtpChallengeRepository;
import repository.ShowSeatRepository;
import repository.UserRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtpBookingWorkflowIntegrationTest {

    private static Long adminId;
    private static Long customerId;
    private static Long theatreId;
    private static Long screenId;
    private static Long movieId;
    private static Long showId;
    private static String customerEmail;

    @BeforeAll
    static void setUp() throws SQLException {
        Database.init();
        customerEmail = "otp-test-" + UUID.randomUUID() + "@example.com";
        try (Connection connection = Database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                adminId = insertUser(connection, "OTP Test Admin", customerEmail + ".admin", "ADMIN");
                customerId = insertUser(connection, "OTP Test Customer", customerEmail, "CUSTOMER");
                theatreId = insertTheatre(connection, adminId);
                screenId = insertScreen(connection, theatreId);
                movieId = insertMovie(connection);
                showId = insertShow(connection, movieId, screenId);
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
            try (PreparedStatement deleteOtp = connection.prepareStatement("DELETE FROM otp_challenges WHERE user_id IN (?, ?)");
                 PreparedStatement deleteSeats = connection.prepareStatement("DELETE FROM show_seats WHERE show_id = ?");
                 PreparedStatement deleteBookings = connection.prepareStatement("DELETE FROM bookings WHERE show_id = ?");
                 PreparedStatement deleteShows = connection.prepareStatement("DELETE FROM shows WHERE show_id = ?");
                 PreparedStatement deleteMovies = connection.prepareStatement("DELETE FROM movies WHERE movie_id = ?");
                 PreparedStatement deleteScreens = connection.prepareStatement("DELETE FROM screens WHERE screen_id = ?");
                 PreparedStatement deleteTheatres = connection.prepareStatement("DELETE FROM theatres WHERE theatre_id = ?");
                 PreparedStatement deleteUsers = connection.prepareStatement("DELETE FROM users WHERE id IN (?, ?)")) {
                deleteOtp.setLong(1, customerId);
                deleteOtp.setLong(2, adminId);
                deleteOtp.executeUpdate();
                deleteSeats.setLong(1, showId);
                deleteSeats.executeUpdate();
                deleteBookings.setLong(1, showId);
                deleteBookings.executeUpdate();
                deleteShows.setLong(1, showId);
                deleteShows.executeUpdate();
                deleteMovies.setLong(1, movieId);
                deleteMovies.executeUpdate();
                deleteScreens.setLong(1, screenId);
                deleteScreens.executeUpdate();
                deleteTheatres.setLong(1, theatreId);
                deleteTheatres.executeUpdate();
                deleteUsers.setLong(1, adminId);
                deleteUsers.setLong(2, customerId);
                deleteUsers.executeUpdate();
                connection.commit();
            }
        }
    }

    @Test
    void successfulOtpVerificationConsumesChallengeOnce() throws SQLException {
        OtpService otpService = new OtpService(new OtpChallengeRepository());
        OtpService.Challenge challenge = otpService.create(customerId, null, OtpPurpose.BOOKING);

        otpService.verifyCode(challenge.token(), customerId, null, OtpPurpose.BOOKING, challenge.code());

        assertThrows(ValidationException.class,
                () -> otpService.verifyCode(challenge.token(), customerId, null,
                        OtpPurpose.BOOKING, challenge.code()));
    }

    @Test
    void invalidOtpDoesNotConsumeChallenge() throws SQLException {
        OtpService otpService = new OtpService(new OtpChallengeRepository());
        OtpService.Challenge challenge = otpService.create(customerId, null, OtpPurpose.BOOKING);

        assertThrows(ValidationException.class,
                () -> otpService.verifyCode(challenge.token(), customerId, null,
                        OtpPurpose.BOOKING, "000000"));

        OtpChallenge persisted = new OtpChallengeRepository()
                .findForUpdate(util.HmacUtil.sha256(challenge.token()))
                .orElseThrow();
        assertEquals(1, persisted.getAttemptCount());
        assertEquals(null, persisted.getConsumedAt());
    }

    @Test
    void resendIsBlockedDuringCooldown() throws SQLException {
        OtpService otpService = new OtpService(new OtpChallengeRepository());
        otpService.create(customerId, null, OtpPurpose.BOOKING);

        assertThrows(ValidationException.class,
                () -> otpService.resend(customerId, null, OtpPurpose.BOOKING));
    }

    @Test
    void bookingHoldDoesNotChargeWalletBeforeOtpVerification() throws SQLException {
        setWalletBalance(customerId, 1000);
        setWalletBalance(adminId, 0);
        BookingResponseData held = holdBooking("A", 1);

        try {
            assertEquals(BookingStatus.AWAITING_OTP, booking(held.bookingId()).getStatus());
            assertEquals(1000, walletBalance(customerId));
            assertEquals(0, walletBalance(adminId));
            assertEquals(1, countSeat(held.bookingId()));
        } finally {
            expireHold(held.bookingId());
        }
    }

    @Test
    void validOtpConfirmsBookingAndChargesWalletOnce() throws SQLException {
        setWalletBalance(customerId, 1000);
        setWalletBalance(adminId, 0);
        BookingResponseData held = holdBooking("A", 2);
        OtpService.Challenge challenge = new OtpService(new OtpChallengeRepository())
                .create(customerId, held.bookingId(), OtpPurpose.BOOKING);

        try {
                BookingResponseData confirmed = confirmWithOtp(held.bookingId(), challenge);

            assertEquals(BookingStatus.CONFIRMED, booking(confirmed.bookingId()).getStatus());
            assertEquals(900, walletBalance(customerId));
            assertEquals(100, walletBalance(adminId));
        } finally {
            deleteBooking(held.bookingId());
        }
    }

    @Test
    void paymentFailureMustLeaveOtpUsableForRetry() throws SQLException {
        setWalletBalance(customerId, 0);
        setWalletBalance(adminId, 0);
        BookingResponseData held = holdBooking("A", 3);
        OtpService otpService = new OtpService(new OtpChallengeRepository());
        OtpService.Challenge challenge = otpService.create(customerId, held.bookingId(), OtpPurpose.BOOKING);

        try {
                assertThrows(ValidationException.class,
                    () -> confirmWithOtp(held.bookingId(), challenge));

            assertEquals(BookingStatus.AWAITING_OTP, booking(held.bookingId()).getStatus());
            assertEquals(0, walletBalance(customerId));
            assertEquals(0, walletBalance(adminId));

            assertTrue(new OtpChallengeRepository()
                    .findForUpdate(util.HmacUtil.sha256(challenge.token()))
                    .orElseThrow()
                    .getConsumedAt() == null,
                    "OTP must remain usable when payment fails");

                setWalletBalance(customerId, 1000);
                BookingResponseData confirmed = confirmWithOtp(held.bookingId(), challenge);
                assertEquals(BookingStatus.CONFIRMED, booking(confirmed.bookingId()).getStatus());
                assertEquals(900, walletBalance(customerId));
                assertEquals(100, walletBalance(adminId));
        } finally {
            deleteBooking(held.bookingId());
        }
    }

    @Test
    void refundFailureMustLeaveCancellationOtpUsableForRetry() throws SQLException {
        setWalletBalance(customerId, 0);
        setWalletBalance(adminId, 0);
        Long bookingId = insertConfirmedBooking(100);
        ShowSeatRepository seats = new ShowSeatRepository();
        ShowSeat seat = new ShowSeat();
        seat.setShowId(showId);
        seat.setRowLabel("B");
        seat.setSeatNumber(4);
        seat.setBookingId(bookingId);
        assertTrue(seats.claimSeat(seat));
        OtpService.Challenge challenge = new OtpService(new OtpChallengeRepository())
                .create(customerId, bookingId, OtpPurpose.CANCELLATION);

        try {
            assertThrows(ValidationException.class,
                    () -> createBookingService().cancelBookingWithOtp(
                            bookingId, customerId, challenge.token(), challenge.code()));
            assertEquals(BookingStatus.CONFIRMED, booking(bookingId).getStatus());
            assertTrue(new OtpChallengeRepository()
                    .findForUpdate(util.HmacUtil.sha256(challenge.token()))
                    .orElseThrow()
                    .getConsumedAt() == null);

            setWalletBalance(adminId, 100);
            createBookingService().cancelBookingWithOtp(
                    bookingId, customerId, challenge.token(), challenge.code());
            assertEquals(BookingStatus.CANCELLED, booking(bookingId).getStatus());
        } finally {
            deleteBooking(bookingId);
        }
    }

    private static BookingResponseData holdBooking(String row, int number) throws SQLException {
        BookingRequest request = new BookingRequest();
        request.setShowId(showId);
        SeatRequest seat = new SeatRequest();
        seat.setRowLabel(row);
        seat.setSeatNumber(number);
        request.setSeats(List.of(seat));
        BookingResponse response = createBookingService().holdTickets(customerId, request);
        return new BookingResponseData(response.getBookingId());
    }

    private static Long insertConfirmedBooking(int total) throws SQLException {
        try (Connection connection = Database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO bookings (user_id, show_id, booking_time, status, total_amount, expires_at) VALUES (?, ?, NOW(), 'CONFIRMED', ?, NULL)",
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, customerId);
            statement.setLong(2, showId);
            statement.setInt(3, total);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private static BookingResponseData confirmWithOtp(Long bookingId, OtpService.Challenge challenge)
            throws SQLException {
        BookingResponse response = createBookingService().confirmHeldBookingWithOtp(
                bookingId, customerId, challenge.token(), challenge.code());
        return new BookingResponseData(response.getBookingId());
    }

    private static BookingService createBookingService() {
        return new BookingService(
                new BookingRepository(),
                new UserRepository(),
                new repository.ShowRepository(),
                new repository.MovieRepository(),
                new repository.ScreenRepository(),
                new repository.TheatreRepository(),
                new ShowSeatRepository(),
                new PaymentService(new UserRepository()),
                new cache.SeatAvailabilityCache(),
                confirmation -> {
                });
    }

    private static void expireHold(Long bookingId) throws SQLException {
        try (Connection connection = Database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE bookings SET status = 'EXPIRED', expires_at = NULL WHERE booking_id = ?")) {
            statement.setLong(1, bookingId);
            statement.executeUpdate();
        }
        deleteBooking(bookingId);
    }

    private static void deleteBooking(Long bookingId) throws SQLException {
        try (Connection connection = Database.openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement deleteOtp = connection.prepareStatement("DELETE FROM otp_challenges WHERE booking_id = ?");
                 PreparedStatement deleteSeats = connection.prepareStatement("DELETE FROM show_seats WHERE booking_id = ?");
                 PreparedStatement deleteBooking = connection.prepareStatement("DELETE FROM bookings WHERE booking_id = ?")) {
                setId(deleteOtp, bookingId);
                deleteOtp.executeUpdate();
                setId(deleteSeats, bookingId);
                deleteSeats.executeUpdate();
                setId(deleteBooking, bookingId);
                deleteBooking.executeUpdate();
                connection.commit();
            }
        }
    }

    private static Booking booking(Long bookingId) {
        return new BookingRepository().findById(bookingId).orElseThrow();
    }

    private static int countSeat(Long bookingId) throws SQLException {
        try (Connection connection = Database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM show_seats WHERE booking_id = ?")) {
            setId(statement, bookingId);
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
            setId(statement, userId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static Long insertUser(Connection connection, String name, String email, String role) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO users (name, email, password_hash, role, wallet_balance) VALUES (?, ?, ?, ?, 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.setString(2, email);
            statement.setString(3, "test-hash");
            statement.setString(4, role);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private static Long insertTheatre(Connection connection, Long ownerId) throws SQLException {
        return insertWithId(connection,
                "INSERT INTO theatres (admin_id, theatre_name, theatre_location) VALUES (?, 'OTP Test Theatre', 'Test Location')",
                ownerId);
    }

    private static Long insertScreen(Connection connection, Long parentId) throws SQLException {
        return insertWithId(connection,
                "INSERT INTO screens (theatre_id, screen_name, row_range, seats_per_row) VALUES (?, 'OTP Test Screen', 'A-B', 10)",
                parentId);
    }

    private static Long insertMovie(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO movies (movie_name, certification, description, director, duration_minutes, ticket_price) VALUES ('OTP Test Movie', 'U', 'Test', 'Test', 120, 100)",
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

    private record BookingResponseData(Long bookingId) {
    }
}
