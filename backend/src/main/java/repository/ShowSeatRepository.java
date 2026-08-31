package repository;

import jakarta.inject.Singleton;
import model.ShowSeat;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;

/*
 * Repository class for handling database operations related to show seats.
 */
@Singleton
public class ShowSeatRepository extends JdbcSupport {

    // Claims/reserves a specific seat for a show. Returns false if the seat is already taken.
    public boolean claimSeat(ShowSeat showSeat) {
        return execute(connection -> {
            String sql = """
                    INSERT INTO show_seats (show_id, row_label, seat_number, booking_id)
                    VALUES (?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, showSeat.getShowId());
                ps.setString(2, showSeat.getRowLabel());
                ps.setInt(3, showSeat.getSeatNumber());
                ps.setLong(4, showSeat.getBookingId());
                ps.executeUpdate();
                return true;
            } catch (SQLIntegrityConstraintViolationException e) {
                return false;
            } catch (SQLException e) {
                if (e.getErrorCode() == 1062) {
                    return false;
                }
                throw e;
            }
        });
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
