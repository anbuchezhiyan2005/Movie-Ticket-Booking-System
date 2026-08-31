package repository;

import jakarta.inject.Singleton;
import model.Show;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/*
 * Repository class for handling database operations related to shows.
 */
@Singleton
public class ShowRepository extends JdbcSupport {

    // Saves a new show to the database and returns the saved entity with its generated ID
    public Show save(Show show) {
        return execute(connection -> {
            String sql = "INSERT INTO shows (movie_id, screen_id, start_time) VALUES (?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, show.getMovieId());
                ps.setLong(2, show.getScreenId());
                ps.setTimestamp(3, Timestamp.valueOf(show.getShowTiming()));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        show.setShowId(keys.getLong(1));
                    }
                }
                return show;
            }
        });
    }

    // Finds a show by its ID
    public Optional<Show> findById(Long showId) {
        return execute(connection -> {
            String sql = "SELECT show_id, movie_id, screen_id, start_time FROM shows WHERE show_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, showId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(map(rs));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    // Retrieves all shows for a specific movie
    public List<Show> findByMovieId(Long movieId) {
        return execute(connection -> {
            String sql = "SELECT show_id, movie_id, screen_id, start_time FROM shows WHERE movie_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, movieId);
                try (ResultSet rs = ps.executeQuery()) {
                    return mapList(rs);
                }
            }
        });
    }

    // Retrieves all shows scheduled on a specific screen
    public List<Show> findByScreenId(Long screenId) {
        return execute(connection -> {
            String sql = "SELECT show_id, movie_id, screen_id, start_time FROM shows WHERE screen_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, screenId);
                try (ResultSet rs = ps.executeQuery()) {
                    return mapList(rs);
                }
            }
        });
    }

    // Updates an existing show's movie, screen, and timing
    public void update(Show show) {
        execute(connection -> {
            String sql = "UPDATE shows SET movie_id = ?, screen_id = ?, start_time = ? WHERE show_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, show.getMovieId());
                ps.setLong(2, show.getScreenId());
                ps.setTimestamp(3, Timestamp.valueOf(show.getShowTiming()));
                ps.setLong(4, show.getShowId());
                ps.executeUpdate();
                return null;
            }
        });
    }

    // Deletes a show by its ID
    public void deleteById(Long showId) {
        execute(connection -> {
            String sql = "DELETE FROM shows WHERE show_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, showId);
                ps.executeUpdate();
                return null;
            }
        });
    }

    // Helper method to map a database result set to a list of Show objects
    private List<Show> mapList(ResultSet rs) throws SQLException {
        List<Show> shows = new ArrayList<>();
        while (rs.next()) {
            shows.add(map(rs));
        }
        return shows;
    }

    // Helper method to map a single result set row to a Show object
    private Show map(ResultSet rs) throws SQLException {
        Show show = new Show();
        show.setShowId(rs.getLong("show_id"));
        show.setMovieId(rs.getLong("movie_id"));
        show.setScreenId(rs.getLong("screen_id"));
        show.setShowTiming(rs.getTimestamp("start_time").toLocalDateTime());
        return show;
    }
}
