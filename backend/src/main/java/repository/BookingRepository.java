package repository;

import enums.BookingStatus;
import jakarta.inject.Singleton;
import model.Booking;
import model.ShowSeat;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/*
 * Repository class for handling database operations related to bookings.
 */
@Singleton
public class BookingRepository extends JdbcSupport {

    // Saves a new booking to the database and returns the saved entity with its generated ID
    public Booking save(Booking booking) {
        return execute(connection -> {
            String sql = """
                    INSERT INTO bookings (user_id, show_id, booking_time, status, total_amount, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, booking.getUserId());
                ps.setLong(2, booking.getShowId());
                ps.setTimestamp(3, Timestamp.valueOf(booking.getBookingTime()));
                ps.setString(4, booking.getStatus().name());
                ps.setInt(5, booking.getTotalAmount());
                if (booking.getExpiresAt() == null) {
                    ps.setTimestamp(6, null);
                } else {
                    ps.setTimestamp(6, Timestamp.valueOf(booking.getExpiresAt()));
                }
                ps.executeUpdate();
                // QUESTION: What's happening here??
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    // QUESTION: Especially here!
                    if (keys.next()) {
                        booking.setBookingId(keys.getLong(1));
                    }
                }
                return booking;
            }
        });
    }

    // Finds a booking by its ID
    public Optional<Booking> findById(Long bookingId) {
        return execute(connection -> {
            String sql = """
                    SELECT booking_id, user_id, show_id, booking_time, status, total_amount, expires_at
                    FROM bookings WHERE booking_id = ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, bookingId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(map(rs));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    public Optional<Booking> findByIdForUpdate(Long bookingId) {
        return execute(connection -> {
            String sql = """
                    SELECT booking_id, user_id, show_id, booking_time, status, total_amount, expires_at
                    FROM bookings WHERE booking_id = ? FOR UPDATE
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, bookingId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    public List<BookingWithSeats> findRecentByUserIdWithSeats(Long userId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Booking limit must be positive");
        }

        return execute(connection -> {
            String sql = """
                    SELECT b.booking_id, b.user_id, b.show_id, b.booking_time,
                           b.status, b.total_amount, b.expires_at,
                              m.movie_name, s.start_time AS show_start_time,
                              m.duration_minutes, sc.screen_name,
                              t.theatre_name, t.theatre_location,
                           ss.show_id AS seat_show_id, ss.row_label AS seat_row_label,
                           ss.seat_number AS seat_number, ss.booking_id AS seat_booking_id
                    FROM (
                        SELECT booking_id, user_id, show_id, booking_time,
                               status, total_amount, expires_at
                        FROM bookings
                        WHERE user_id = ?
                        ORDER BY booking_time DESC, booking_id DESC
                        LIMIT ?
                    ) b
                    JOIN shows s ON s.show_id = b.show_id
                    JOIN movies m ON m.movie_id = s.movie_id
                    JOIN screens sc ON sc.screen_id = s.screen_id
                    JOIN theatres t ON t.theatre_id = sc.theatre_id
                    LEFT JOIN show_seats ss ON ss.booking_id = b.booking_id
                    ORDER BY b.booking_time DESC, b.booking_id DESC,
                             ss.row_label, ss.seat_number
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, userId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    Map<Long, BookingWithSeats> bookings = new LinkedHashMap<>();
                    Map<Long, List<ShowSeat>> seatsByBooking = new LinkedHashMap<>();
                    while (rs.next()) {
                        Long bookingId = rs.getLong("booking_id");
                        long seatBookingId = rs.getLong("seat_booking_id");
                        boolean hasSeat = !rs.wasNull();

                        if (!bookings.containsKey(bookingId)) {
                            bookings.put(bookingId, new BookingWithSeats(
                                    map(rs),
                                    List.of(),
                                    rs.getString("movie_name"),
                                    rs.getTimestamp("show_start_time").toLocalDateTime(),
                                    rs.getInt("duration_minutes"),
                                    rs.getString("screen_name"),
                                    rs.getString("theatre_name"),
                                    rs.getString("theatre_location")));
                        }

                        if (hasSeat) {
                            ShowSeat seat = new ShowSeat();
                            seat.setShowId(rs.getLong("seat_show_id"));
                            seat.setRowLabel(rs.getString("seat_row_label"));
                            seat.setSeatNumber(rs.getInt("seat_number"));
                            seat.setBookingId(seatBookingId);
                            seatsByBooking.computeIfAbsent(bookingId, ignored -> new ArrayList<>())
                                    .add(seat);
                        }
                    }

                    List<BookingWithSeats> result = new ArrayList<>(bookings.size());
                        bookings.forEach((bookingId, booking) -> result.add(new BookingWithSeats(
                            booking.booking(),
                            seatsByBooking.getOrDefault(bookingId, List.of()),
                            booking.movieName(),
                            booking.showStartTime(),
                            booking.durationMinutes(),
                            booking.screenName(),
                            booking.theatreName(),
                            booking.theatreLocation())));
                    return result;
                }
            }
        });
    }

    public void expirePendingBookings(LocalDateTime now) {
        execute(connection -> {
            String sql = """
                    UPDATE bookings
                    SET status = 'EXPIRED', expires_at = NULL
                    WHERE status IN ('PENDING', 'AWAITING_OTP')
                      AND expires_at IS NOT NULL
                      AND expires_at < ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setTimestamp(1, Timestamp.valueOf(now));
                ps.executeUpdate();
                return null;
            }
        });
    }

    // TESTING ONLY
    public boolean existsByShowId(Long showId) {
        return execute(connection -> {
            String sql = "SELECT 1 FROM bookings WHERE show_id = ? LIMIT 1";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, showId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    // TESTING ONLY
    public boolean existsActiveBookingForShow(Long showId) {
        return execute(connection -> {
            String sql = "SELECT 1 FROM bookings WHERE show_id = ? AND status NOT IN ('CANCELLED', 'EXPIRED') LIMIT 1";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, showId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    // TESTING ONLY
    public boolean existsConfirmedBookingForShow(Long showId) {
        return execute(connection -> {
            String sql = "SELECT 1 FROM bookings WHERE show_id = ? AND status = 'CONFIRMED' LIMIT 1";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, showId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public boolean existsByScreenId(Long screenId) {
        return execute(connection -> {
            String sql = """
                    SELECT 1 FROM bookings b
                    JOIN shows s ON s.show_id = b.show_id
                    WHERE s.screen_id = ? LIMIT 1
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, screenId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public boolean existsConfirmedBookingForTheatre(Long theatreId) {
        return execute(connection -> {
            String sql = """
                    SELECT 1 FROM bookings b
                    JOIN shows s ON s.show_id = b.show_id
                    JOIN screens sc ON sc.screen_id = s.screen_id
                    WHERE sc.theatre_id = ? AND b.status = 'CONFIRMED' LIMIT 1
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, theatreId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public void update(Booking booking) {
        execute(connection -> {
            String sql = """
                    UPDATE bookings
                    SET status = ?, total_amount = ?, expires_at = ?
                    WHERE booking_id = ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, booking.getStatus().name());
                ps.setInt(2, booking.getTotalAmount());
                if (booking.getExpiresAt() == null) {
                    ps.setTimestamp(3, null);
                } else {
                    ps.setTimestamp(3, Timestamp.valueOf(booking.getExpiresAt()));
                }
                ps.setLong(4, booking.getBookingId());
                ps.executeUpdate();
                return null;
            }
        });
    }

    // Helper method to map a database result set row to a Booking object
    private Booking map(ResultSet rs) throws SQLException {
        Booking booking = new Booking();
        booking.setBookingId(rs.getLong("booking_id"));
        booking.setUserId(rs.getLong("user_id"));
        booking.setShowId(rs.getLong("show_id"));
        booking.setBookingTime(rs.getTimestamp("booking_time").toLocalDateTime());
        booking.setStatus(BookingStatus.valueOf(rs.getString("status")));
        booking.setTotalAmount(rs.getInt("total_amount"));
        Timestamp expires = rs.getTimestamp("expires_at");
        booking.setExpiresAt(expires == null ? null : expires.toLocalDateTime());
        return booking;
    }

    public record BookingWithSeats(
            Booking booking,
            List<ShowSeat> seats,
            String movieName,
            LocalDateTime showStartTime,
            int durationMinutes,
            String screenName,
            String theatreName,
            String theatreLocation) {
    }
}
