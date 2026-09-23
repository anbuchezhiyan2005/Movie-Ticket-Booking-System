package repository;

import jakarta.inject.Singleton;
import model.Movie;
import model.Screen;
import model.Show;
import model.Theatre;

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
            String sql = "SELECT show_id, movie_id AS show_movie_id, screen_id AS show_screen_id, start_time FROM shows WHERE show_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, showId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapShow(rs));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    // Loads a show together with its screen, movie, and theatre in a single query.
    public Optional<ShowWithDetails> findWithDetails(Long showId) {
        return execute(connection -> {
            String sql = """
                    SELECT  s.show_id, 
                            s.movie_id AS show_movie_id, 
                            s.screen_id AS show_screen_id, 
                            s.start_time,
                            sc.screen_id AS scr_screen_id, 
                            sc.theatre_id AS scr_theatre_id, 
                            sc.screen_name,
                            sc.row_range, 
                            sc.seats_per_row,
                            m.movie_id AS mov_id, 
                            m.movie_name, 
                            m.certification, 
                            m.description, 
                            m.director,
                            m.duration_minutes, 
                            m.ticket_price,
                            t.theatre_id AS th_id, 
                            t.admin_id, 
                            t.theatre_name, 
                            t.theatre_location
                    FROM shows s
                    JOIN screens sc ON sc.screen_id = s.screen_id
                    JOIN movies m ON m.movie_id = s.movie_id
                    JOIN theatres t ON t.theatre_id = sc.theatre_id
                    WHERE s.show_id = ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, showId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    Show show = mapShow(rs);
                    Screen screen = new Screen();
                    screen.setScreenId(rs.getLong("scr_screen_id"));
                    screen.setTheatreId(rs.getLong("scr_theatre_id"));
                    screen.setScreenName(rs.getString("screen_name"));
                    screen.setRowRange(rs.getString("row_range"));
                    screen.setSeatsPerRow(rs.getInt("seats_per_row"));

                    Movie movie = new Movie();
                    movie.setMovieId(rs.getLong("mov_id"));
                    movie.setMovieName(rs.getString("movie_name"));
                    movie.setCertification(rs.getString("certification"));
                    movie.setDescription(rs.getString("description"));
                    movie.setDirector(rs.getString("director"));
                    movie.setDurationInMinutes(rs.getInt("duration_minutes"));
                    movie.setTicketPrice(rs.getInt("ticket_price"));

                    Theatre theatre = new Theatre();
                    theatre.setTheatreId(rs.getLong("th_id"));
                    theatre.setAdminId(rs.getLong("admin_id"));
                    theatre.setTheatreName(rs.getString("theatre_name"));
                    theatre.setTheatreLocation(rs.getString("theatre_location"));
                    return Optional.of(new ShowWithDetails(show, screen, movie, theatre));
                }
            }
        });
    }

    // Retrieves all shows for a specific movie
    public List<Show> findByMovieId(Long movieId) {
        return execute(connection -> {
            String sql = "SELECT show_id, movie_id AS show_movie_id, screen_id AS show_screen_id, start_time FROM shows WHERE movie_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, movieId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Show> shows = new ArrayList<>();
                    while (rs.next()) {
                        shows.add(mapShow(rs));
                    }
                    return shows;
                }
            }
        });
    }

    // Retrieves all shows scheduled on a specific screen
    public List<Show> findByScreenId(Long screenId) {
        return execute(connection -> {
            String sql = "SELECT show_id, movie_id AS show_movie_id, screen_id AS show_screen_id, start_time FROM shows WHERE screen_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, screenId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Show> shows = new ArrayList<>();
                    while (rs.next()) {
                        shows.add(mapShow(rs));
                    }
                    return shows;
                }
            }
        });
    }

    public boolean existsUnfinishedShowOnScreen(Long screenId, LocalDateTime now) {
        return execute(connection -> {
            String sql = """
                    SELECT EXISTS (
                        SELECT 1
                        FROM shows s
                        JOIN movies m ON m.movie_id = s.movie_id
                        WHERE s.screen_id = ?
                          AND DATE_ADD(
                                s.start_time,
                                INTERVAL m.duration_minutes MINUTE
                              ) > ?
                    )
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, screenId);
                ps.setTimestamp(2, Timestamp.valueOf(now));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBoolean(1);
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

    // Helper method to map a single result set row to a Show object
    private Show mapShow(ResultSet rs) throws SQLException {
        Show show = new Show();
        show.setShowId(rs.getLong("show_id"));
        show.setMovieId(rs.getLong("show_movie_id"));
        show.setScreenId(rs.getLong("show_screen_id"));
        show.setShowTiming(rs.getTimestamp("start_time").toLocalDateTime());
        return show;
    }

    /** Aggregates a show with its screen, movie, and theatre (one joined query). */
    public record ShowWithDetails(Show show, Screen screen, Movie movie, Theatre theatre) {
    }
}
