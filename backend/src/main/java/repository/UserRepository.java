package repository;

import enums.Role;
import jakarta.inject.Singleton;
import model.User;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/*
 * Repository class for handling database operations related to users.
 */
@Singleton
public class UserRepository extends JdbcSupport {

    // Finds a user by their ID
    public Optional<User> findById(Long userId) {
        return execute(connection -> {
            String sql = "SELECT id, name, email, password_hash, role, wallet_balance FROM users WHERE id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(map(rs));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    // Finds a user by their email address
    public Optional<User> findByEmail(String email) {
        return execute(connection -> {
            String sql = "SELECT id, name, email, password_hash, role, wallet_balance FROM users WHERE email = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, email);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(map(rs));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    // Saves a new user to the database and returns the saved entity with its generated ID
    public User save(User user) {
        return execute(connection -> {
            String sql = """
                    INSERT INTO users (name, email, password_hash, role, wallet_balance)
                    VALUES (?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, user.getName());
                ps.setString(2, user.getEmail());
                ps.setString(3, user.getPasswordHash());
                ps.setString(4, user.getRole().name());
                ps.setInt(5, user.getWalletBalance());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        user.setId(keys.getLong(1));
                    }
                }
                return user;
            }
        });
    }

    // Updates a user's wallet balance
    public void updateWalletBalance(Long userId, int newBalance) {
        execute(connection -> {
            String sql = "UPDATE users SET wallet_balance = ? WHERE id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, newBalance);
                ps.setLong(2, userId);
                ps.executeUpdate();
                return null;
            }
        });
    }

    public boolean debitWalletBalance(Long userId, int amount) {
        return execute(connection -> {
            String sql = "UPDATE users SET wallet_balance = wallet_balance - ? WHERE id = ? AND wallet_balance >= ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, amount);
                ps.setLong(2, userId);
                ps.setInt(3, amount);
                return ps.executeUpdate() == 1;
            }
        });
    }

    public void creditWalletBalance(Long userId, int amount) {
        execute(connection -> {
            String sql = "UPDATE users SET wallet_balance = wallet_balance + ? WHERE id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, amount);
                ps.setLong(2, userId);
                if (ps.executeUpdate() != 1) {
                    throw new IllegalStateException("User not found");
                }
                return null;
            }
        });
    }

    // Helper method to map a database result set row to a User object
    private User map(ResultSet rs) throws SQLException {
        User user = new User();
        user.setId(rs.getLong("id"));
        user.setName(rs.getString("name"));
        user.setEmail(rs.getString("email"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setRole(Role.valueOf(rs.getString("role")));
        user.setWalletBalance(rs.getInt("wallet_balance"));
        return user;
    }
}
