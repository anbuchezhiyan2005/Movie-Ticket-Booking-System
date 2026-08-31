package repository;

import jakarta.inject.Singleton;
import model.Theatre;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/*
 * Repository class for handling database operations related to theatres.
 */
@Singleton
public class TheatreRepository extends JdbcSupport {

    // Saves a new theatre to the database and returns the saved entity with its generated ID
    public Theatre save(Theatre theatre) {
        return execute(connection -> {
            String sql = """
                    INSERT INTO theatres (admin_id, theatre_name, theatre_location)
                    VALUES (?, ?, ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, theatre.getAdminId());
                ps.setString(2, theatre.getTheatreName());
                ps.setString(3, theatre.getTheatreLocation());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        theatre.setTheatreId(keys.getLong(1));
                    }
                }
                return theatre;
            }
        });
    }

    // Finds a theatre by its ID
    public Optional<Theatre> findById(Long theatreId) {
        return execute(connection -> {
            String sql = "SELECT theatre_id, admin_id, theatre_name, theatre_location FROM theatres WHERE theatre_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, theatreId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(map(rs));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    // Retrieves all theatres owned by a specific admin
    public List<Theatre> findByAdminId(Long adminId) {
        return execute(connection -> {
            String sql = "SELECT theatre_id, admin_id, theatre_name, theatre_location FROM theatres WHERE admin_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, adminId);
                try (ResultSet rs = ps.executeQuery()) {
                    return mapList(rs);
                }
            }
        });
    }

    // Retrieves all unique theatres showing a specific movie
    public List<Theatre> findByMovieId(Long movieId) {
        return execute(connection -> {
            String sql = """
                    SELECT DISTINCT t.theatre_id, t.admin_id, t.theatre_name, t.theatre_location
                    FROM theatres t
                    JOIN screens sc ON sc.theatre_id = t.theatre_id
                    JOIN shows sh ON sh.screen_id = sc.screen_id
                    WHERE sh.movie_id = ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, movieId);
                try (ResultSet rs = ps.executeQuery()) {
                    return mapList(rs);
                }
            }
        });
    }

    // Updates an existing theatre's name and location
    public void update(Theatre theatre) {
        execute(connection -> {
            String sql = "UPDATE theatres SET theatre_name = ?, theatre_location = ? WHERE theatre_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, theatre.getTheatreName());
                ps.setString(2, theatre.getTheatreLocation());
                ps.setLong(3, theatre.getTheatreId());
                ps.executeUpdate();
                return null;
            }
        });
    }

    // Deletes a theatre by its ID
    public void deleteById(Long theatreId) {
        execute(connection -> {
            String sql = "DELETE FROM theatres WHERE theatre_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, theatreId);
                ps.executeUpdate();
                return null;
            }
        });
    }

    // Helper method to map a result set to a list of Theatre objects
    private List<Theatre> mapList(ResultSet rs) throws SQLException {
        List<Theatre> theatres = new ArrayList<>();
        while (rs.next()) {
            theatres.add(map(rs));
        }
        return theatres;
    }

    // Helper method to map a single result set row to a Theatre object
    private Theatre map(ResultSet rs) throws SQLException {
        Theatre theatre = new Theatre();
        theatre.setTheatreId(rs.getLong("theatre_id"));
        theatre.setAdminId(rs.getLong("admin_id"));
        theatre.setTheatreName(rs.getString("theatre_name"));
        theatre.setTheatreLocation(rs.getString("theatre_location"));
        return theatre;
    }
}
