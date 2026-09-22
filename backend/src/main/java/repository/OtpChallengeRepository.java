package repository;

import enums.OtpPurpose;
import jakarta.inject.Singleton;
import model.OtpChallenge;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Singleton
public class OtpChallengeRepository extends JdbcSupport {

    public OtpChallenge save(OtpChallenge challenge) {
        return execute(connection -> {
            String sql = """
                    INSERT INTO otp_challenges
                        (user_id, booking_id, purpose, challenge_token_hash, otp_hash,
                         expires_at, attempt_count, max_attempts, last_sent_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, challenge.getUserId());
                if (challenge.getBookingId() == null) statement.setNull(2, java.sql.Types.BIGINT);
                else statement.setLong(2, challenge.getBookingId());
                statement.setString(3, challenge.getPurpose().name());
                statement.setString(4, challenge.getChallengeTokenHash());
                statement.setString(5, challenge.getOtpHash());
                statement.setTimestamp(6, Timestamp.valueOf(challenge.getExpiresAt()));
                statement.setInt(7, challenge.getAttemptCount());
                statement.setInt(8, challenge.getMaxAttempts());
                statement.setTimestamp(9, Timestamp.valueOf(challenge.getLastSentAt()));
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) challenge.setId(keys.getLong(1));
                }
                return challenge;
            }
        });
    }

    public Optional<OtpChallenge> findForUpdate(String tokenHash) {
        return execute(connection -> {
            String sql = """
                    SELECT id, user_id, booking_id, purpose, challenge_token_hash, otp_hash,
                           expires_at, attempt_count, max_attempts, last_sent_at, consumed_at, created_at
                    FROM otp_challenges
                    WHERE challenge_token_hash = ?
                    FOR UPDATE
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tokenHash);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
                }
            }
        });
    }

    public Optional<OtpChallenge> findActive(Long userId, Long bookingId, OtpPurpose purpose) {
        return execute(connection -> {
            String sql = """
                    SELECT id, user_id, booking_id, purpose, challenge_token_hash, otp_hash,
                           expires_at, attempt_count, max_attempts, last_sent_at, consumed_at, created_at
                    FROM otp_challenges
                    WHERE user_id = ? AND purpose = ? AND consumed_at IS NULL
                      AND (booking_id = ? OR (booking_id IS NULL AND ? IS NULL))
                    ORDER BY id DESC LIMIT 1
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, userId);
                statement.setString(2, purpose.name());
                if (bookingId == null) statement.setNull(3, java.sql.Types.BIGINT);
                else statement.setLong(3, bookingId);
                if (bookingId == null) statement.setNull(4, java.sql.Types.BIGINT);
                else statement.setLong(4, bookingId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
                }
            }
        });
    }

    public void supersede(Long id, LocalDateTime supersededAt) {
        execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE otp_challenges SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL")) {
                statement.setTimestamp(1, Timestamp.valueOf(supersededAt));
                statement.setLong(2, id);
                statement.executeUpdate();
                return null;
            }
        });
    }

    public void deleteByBookingIds(List<Long> bookingIds) {
        if (bookingIds == null || bookingIds.isEmpty()) {
            return;
        }

        execute(connection -> {
            String placeholders = String.join(", ", java.util.Collections.nCopies(bookingIds.size(), "?"));
            String sql = "DELETE FROM otp_challenges WHERE booking_id IN (" + placeholders + ")";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < bookingIds.size(); index++) {
                    statement.setLong(index + 1, bookingIds.get(index));
                }
                statement.executeUpdate();
                return null;
            }
        });
    }

    public void deleteByExpiredBooking(LocalDateTime now) {
        execute(connection -> {
            String sql = """
                    DELETE oc
                    FROM otp_challenges oc
                    JOIN bookings b ON b.booking_id = oc.booking_id
                    WHERE b.status IN ('PENDING', 'AWAITING_OTP')
                      AND b.expires_at IS NOT NULL
                      AND b.expires_at < ?
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setTimestamp(1, Timestamp.valueOf(now));
                statement.executeUpdate();
                return null;
            }
        });
    }

    public void incrementAttempts(Long id) {
        execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE otp_challenges SET attempt_count = attempt_count + 1 WHERE id = ?")) {
                statement.setLong(1, id);
                statement.executeUpdate();
                return null;
            }
        });
    }

    public void consume(Long id, LocalDateTime consumedAt) {
        execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE otp_challenges SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL")) {
                statement.setTimestamp(1, Timestamp.valueOf(consumedAt));
                statement.setLong(2, id);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("OTP challenge was already consumed");
                }
                return null;
            }
        });
    }

    private OtpChallenge map(ResultSet resultSet) throws SQLException {
        OtpChallenge challenge = new OtpChallenge();
        challenge.setId(resultSet.getLong("id"));
        challenge.setUserId(resultSet.getLong("user_id"));
        long bookingId = resultSet.getLong("booking_id");
        challenge.setBookingId(resultSet.wasNull() ? null : bookingId);
        challenge.setPurpose(OtpPurpose.valueOf(resultSet.getString("purpose")));
        challenge.setChallengeTokenHash(resultSet.getString("challenge_token_hash"));
        challenge.setOtpHash(resultSet.getString("otp_hash"));
        challenge.setExpiresAt(resultSet.getTimestamp("expires_at").toLocalDateTime());
        challenge.setAttemptCount(resultSet.getInt("attempt_count"));
        challenge.setMaxAttempts(resultSet.getInt("max_attempts"));
        challenge.setLastSentAt(resultSet.getTimestamp("last_sent_at").toLocalDateTime());
        Timestamp consumedAt = resultSet.getTimestamp("consumed_at");
        challenge.setConsumedAt(consumedAt == null ? null : consumedAt.toLocalDateTime());
        challenge.setCreatedAt(resultSet.getTimestamp("created_at").toLocalDateTime());
        return challenge;
    }
}