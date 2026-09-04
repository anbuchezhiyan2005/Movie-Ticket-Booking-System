package repository;

import enums.BookingStatus;
import jakarta.inject.Singleton;
import model.Booking;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
                try (ResultSet keys = ps.getGeneratedKeys()) {
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

    // Retrieves all bookings made by a specific user
    public List<Booking> findByUserId(Long userId) {
        return execute(connection -> {
            String sql = """
                    SELECT booking_id, user_id, show_id, booking_time, status, total_amount, expires_at
                    FROM bookings WHERE user_id = ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Booking> bookings = new ArrayList<>();
                    while (rs.next()) {
                        bookings.add(map(rs));
                    }
                    return bookings;
                }
            }
        });
    }

    // Finds bookings that are still PENDING and have passed their expiry time
    public List<Booking> findExpiredPending(LocalDateTime now) {
        return execute(connection -> {
            String sql = """
                    SELECT booking_id, user_id, show_id, booking_time, status, total_amount, expires_at
                    FROM bookings
                    WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at < ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setTimestamp(1, Timestamp.valueOf(now));
                try (ResultSet rs = ps.executeQuery()) {
                    List<Booking> bookings = new ArrayList<>();
                    while (rs.next()) {
                        bookings.add(map(rs));
                    }
                    return bookings;
                }
            }
        });
    }

    // Checks if any bookings exist for a specific show
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

    public boolean existsByTheatreId(Long theatreId) {
        return execute(connection -> {
            String sql = """
                    SELECT 1 FROM bookings b
                    JOIN shows s ON s.show_id = b.show_id
                    JOIN screens sc ON sc.screen_id = s.screen_id
                    WHERE sc.theatre_id = ? LIMIT 1
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, theatreId);
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

    // Updates an existing booking's status, amount, and expiry time
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
}
