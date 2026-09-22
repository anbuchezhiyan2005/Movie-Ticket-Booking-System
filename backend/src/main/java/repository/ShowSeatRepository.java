package repository;

import jakarta.inject.Singleton;
import model.ShowSeat;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * Repository class for handling database operations related to show seats.
 */
@Singleton
public class ShowSeatRepository extends JdbcSupport {

    // Claims/reserves a specific seat for a show. Returns false if the seat is already taken.
    public boolean claimSeat(ShowSeat showSeat) {
        return execute(connection -> {
            String existingSeatSql = """
                SELECT b.status AS booking_status, 
                    (b.expires_at < UTC_TIMESTAMP()) AS is_expired
                FROM show_seats ss
                JOIN bookings b ON b.booking_id = ss.booking_id
                WHERE ss.show_id = ? AND ss.row_label = ? AND ss.seat_number = ?
                FOR UPDATE
                """;

            try (PreparedStatement stmt = connection.prepareStatement(existingSeatSql)) {
                stmt.setLong(1, showSeat.getShowId());
                stmt.setString(2, showSeat.getRowLabel());
                stmt.setInt(3, showSeat.getSeatNumber());

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String status = rs.getString("booking_status");
                        boolean isExpired = rs.getBoolean("is_expired");

                        boolean isHold = "PENDING".equals(status) || "AWAITING_OTP".equals(status);
                        boolean isSeatOccupied = !isHold || !isExpired;

                        // If the seat has a permanent booking OR an active/unexpired hold, reject claim
                        if (isSeatOccupied) {
                            return false;
                        }

                        // Stale/expired hold found: disassociate seat from expired booking
                        deleteSeat(connection, showSeat);
                    }
                }
            

                String insertSql = """
                        INSERT INTO show_seats (show_id, row_label, seat_number, booking_id)
                        VALUES (?, ?, ?, ?)
                        """;
                try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                    insert.setLong(1, showSeat.getShowId());
                    insert.setString(2, showSeat.getRowLabel());
                    insert.setInt(3, showSeat.getSeatNumber());
                    insert.setLong(4, showSeat.getBookingId());
                    insert.executeUpdate();
                    return true;
                } catch (SQLIntegrityConstraintViolationException e) {
                    return false;
                } catch (SQLException e) {
                    if (e.getErrorCode() == 1062) {
                        return false;
                    }
                    throw e;
                }
            }
        });
    }

    private void deleteSeat(java.sql.Connection connection, ShowSeat showSeat) throws SQLException {
        String sql = """
                DELETE FROM show_seats
                WHERE show_id = ? AND row_label = ? AND seat_number = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, showSeat.getShowId());
            statement.setString(2, showSeat.getRowLabel());
            statement.setInt(3, showSeat.getSeatNumber());
            statement.executeUpdate();
        }
    }

    // Retrieves all booked/claimed seats for a specific show
    public List<ShowSeat> findByShowId(Long showId) {
        return execute(connection -> {
            String sql = """
                    SELECT show_id, row_label, seat_number, booking_id
                    FROM show_seats WHERE show_id = ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, showId);
                try (ResultSet rs = ps.executeQuery()) {
                    return mapList(rs);
                }
            }
        });
    }

    // Retrieves claimed seats and distinguishes temporary holds from confirmed bookings.
    public Map<String, String> findStatusByShowId(Long showId) {
        return execute(connection -> {
            String sql = """
                    SELECT ss.row_label, ss.seat_number, b.status
                    FROM show_seats ss
                    JOIN bookings b ON b.booking_id = ss.booking_id
                    WHERE ss.show_id = ?
                      AND (b.status = 'CONFIRMED'
                           OR (b.status IN ('PENDING', 'AWAITING_OTP')
                               AND b.expires_at > UTC_TIMESTAMP()))
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, showId);
                try (ResultSet rs = ps.executeQuery()) {
                    Map<String, String> statuses = new HashMap<>();
                    while (rs.next()) {
                        String key = rs.getString("row_label") + "-" + rs.getInt("seat_number");
                        statuses.put(key, rs.getString("status"));
                    }
                    return statuses;
                }
            }
        });
    }

    // Retrieves all seats claimed under a specific booking ID
    public List<ShowSeat> findByBookingId(Long bookingId) {
        return execute(connection -> {
            String sql = """
                    SELECT show_id, row_label, seat_number, booking_id
                    FROM show_seats WHERE booking_id = ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, bookingId);
                try (ResultSet rs = ps.executeQuery()) {
                    return mapList(rs);
                }
            }
        });
    }

    public List<ShowSeat> findByExpiredBooking(LocalDateTime now) {
        return execute(connection -> {
            String sql = """
                    SELECT ss.show_id, ss.row_label, ss.seat_number, ss.booking_id
                    FROM show_seats ss
                    JOIN bookings b ON b.booking_id = ss.booking_id
                    WHERE b.status IN ('PENDING', 'AWAITING_OTP')
                      AND b.expires_at IS NOT NULL
                      AND b.expires_at < ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setTimestamp(1, java.sql.Timestamp.valueOf(now));
                try (ResultSet rs = ps.executeQuery()) {
                    // util function to map result set to list of ShowSeat objects
                    return mapList(rs);
                }
            }
        });
    }

    public void deleteByExpiredBooking(LocalDateTime now) {
        execute(connection -> {
            String sql = """
                    DELETE ss
                    FROM show_seats ss
                    JOIN bookings b ON b.booking_id = ss.booking_id
                    WHERE b.status IN ('PENDING', 'AWAITING_OTP')
                      AND b.expires_at IS NOT NULL
                      AND b.expires_at < ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setTimestamp(1, java.sql.Timestamp.valueOf(now));
                ps.executeUpdate();
                return null;
            }
        });
    }

    // Deletes/releases all seats claimed under a specific booking ID
    public void deleteByBookingId(Long bookingId) {
        execute(connection -> {
            String sql = "DELETE FROM show_seats WHERE booking_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, bookingId);
                ps.executeUpdate();
                return null;
            }
        });
    }

    // Helper method to map a database result set to a list of ShowSeat objects
    private List<ShowSeat> mapList(ResultSet rs) throws SQLException {
        List<ShowSeat> seats = new ArrayList<>();
        while (rs.next()) {
            ShowSeat seat = new ShowSeat();
            seat.setShowId(rs.getLong("show_id"));
            seat.setRowLabel(rs.getString("row_label"));
            seat.setSeatNumber(rs.getInt("seat_number"));
            seat.setBookingId(rs.getLong("booking_id"));
            seats.add(seat);
        }
        return seats;
    }
}
