package repository;

import jakarta.inject.Singleton;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

@Singleton
public class BookingGateTokenRepository extends JdbcSupport {

    public void save(Long bookingId, String tokenHash, LocalDateTime expiresAt) {
        execute(connection -> {
            String sql = """
                    INSERT INTO booking_gate_tokens (booking_id, token_hash, status, expires_at)
                    VALUES (?, ?, 'ISSUED', ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, bookingId);
                statement.setString(2, tokenHash);
                statement.setTimestamp(3, Timestamp.valueOf(expiresAt));
                statement.executeUpdate();
                return null;
            }
        });
    }

    public boolean consume(String tokenHash, String deviceId) {
        return execute(connection -> {
            String sql = """
                    UPDATE booking_gate_tokens token
                    JOIN bookings booking ON booking.booking_id = token.booking_id
                    JOIN shows show_data ON show_data.show_id = booking.show_id
                    JOIN movies movie ON movie.movie_id = show_data.movie_id
                    SET token.status = 'USED', token.scanned_at = CURRENT_TIMESTAMP,
                        token.scanned_by_device = ?
                    WHERE token.token_hash = ?
                      AND token.status = 'ISSUED'
                      AND booking.status = 'CONFIRMED'
                      AND token.expires_at > CURRENT_TIMESTAMP
                      AND CURRENT_TIMESTAMP < DATE_ADD(show_data.start_time, INTERVAL movie.duration_minutes MINUTE)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, deviceId);
                statement.setString(2, tokenHash);
                return statement.executeUpdate() == 1;
            }
        });
    }

    public Optional<Rejection> findReason(String tokenHash) {
        return execute(connection -> {
            String sql = """
                    SELECT token.status, token.expires_at, booking.status AS booking_status,
                           DATE_ADD(show_data.start_time, INTERVAL movie.duration_minutes MINUTE) AS show_end
                    FROM booking_gate_tokens token
                    JOIN bookings booking ON booking.booking_id = token.booking_id
                    JOIN shows show_data ON show_data.show_id = booking.show_id
                    JOIN movies movie ON movie.movie_id = show_data.movie_id
                    WHERE token.token_hash = ?
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tokenHash);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    if ("USED".equals(resultSet.getString("status"))) {
                        return Optional.of(Rejection.USED);
                    }
                    if (!"CONFIRMED".equals(resultSet.getString("booking_status"))) {
                        return Optional.of(Rejection.BOOKING_CANCELLED);
                    }
                    if (resultSet.getTimestamp("expires_at").toLocalDateTime().isBefore(LocalDateTime.now())) {
                        return Optional.of(Rejection.TOKEN_EXPIRED);
                    }
                    if (resultSet.getTimestamp("show_end").toLocalDateTime().isBefore(LocalDateTime.now())) {
                        return Optional.of(Rejection.SHOW_EXPIRED);
                    }
                    return Optional.of(Rejection.INVALID_TOKEN);
                }
            }
        });
    }

    public enum Rejection {
        USED,
        BOOKING_CANCELLED,
        TOKEN_EXPIRED,
        SHOW_EXPIRED,
        INVALID_TOKEN
    }
}
