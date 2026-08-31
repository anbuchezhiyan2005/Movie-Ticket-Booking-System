package repository;

import jakarta.inject.Singleton;
import model.Screen;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/*
 * Repository class for handling database operations related to screens.
 */
@Singleton
public class ScreenRepository extends JdbcSupport {

    // Saves a new screen to the database and returns the saved entity with its generated ID
    public Screen save(Screen screen) {
        return execute(connection -> {
            String sql = """
                    INSERT INTO screens (theatre_id, screen_name, row_range, seats_per_row)
                    VALUES (?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, screen.getTheatreId());
                ps.setString(2, screen.getScreenName());
                ps.setString(3, screen.getRowRange());
                ps.setInt(4, screen.getSeatsPerRow());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        screen.setScreenId(keys.getLong(1));
                    }
                }
                return screen;
            }
        });
    }

    // Finds a screen by its ID
    public Optional<Screen> findById(Long screenId) {
        return execute(connection -> {
            String sql = """
                    SELECT screen_id, theatre_id, screen_name, row_range, seats_per_row
                    FROM screens WHERE screen_id = ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, screenId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(map(rs));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    // Retrieves all screens belonging to a specific theatre
    public List<Screen> findByTheatreId(Long theatreId) {
        return execute(connection -> {
            String sql = """
                    SELECT screen_id, theatre_id, screen_name, row_range, seats_per_row
                    FROM screens WHERE theatre_id = ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, theatreId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Screen> screens = new ArrayList<>();
                    while (rs.next()) {
                        screens.add(map(rs));
                    }
                    return screens;
                }
            }
        });
    }

    // Updates an existing screen's information
    public void update(Screen screen) {
        execute(connection -> {
            String sql = """
                    UPDATE screens
                    SET screen_name = ?, row_range = ?, seats_per_row = ?
                    WHERE screen_id = ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, screen.getScreenName());
                ps.setString(2, screen.getRowRange());
                ps.setInt(3, screen.getSeatsPerRow());
                ps.setLong(4, screen.getScreenId());
                ps.executeUpdate();
                return null;
            }
        });
    }

    // Deletes a screen by its ID
    public void deleteById(Long screenId) {
        execute(connection -> {
            String sql = "DELETE FROM screens WHERE screen_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, screenId);
                ps.executeUpdate();
                return null;
            }
        });
    }

    // Helper method to map a database result set row to a Screen object
    private Screen map(ResultSet rs) throws SQLException {
        Screen screen = new Screen();
        screen.setScreenId(rs.getLong("screen_id"));
        screen.setTheatreId(rs.getLong("theatre_id"));
        screen.setScreenName(rs.getString("screen_name"));
        screen.setRowRange(rs.getString("row_range"));
        screen.setSeatsPerRow(rs.getInt("seats_per_row"));
        return screen;
    }
}
