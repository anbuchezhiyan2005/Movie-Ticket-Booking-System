package repository;

import jakarta.inject.Singleton;
import model.Movie;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/*
 * Repository class for handling database operations related to movies.
 */
@Singleton
public class MovieRepository extends JdbcSupport {

    // Finds a movie by its ID
    public Optional<Movie> findById(Long movieId) {
        return execute(connection -> {
            String sql = """
                    SELECT movie_id, movie_name, certification, description, director,
                           duration_minutes, ticket_price
                    FROM movies WHERE movie_id = ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, movieId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(map(rs));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    // Retrieves all movies from the database
    public List<Movie> findAll() {
        return execute(connection -> {
            String sql = """
                    SELECT movie_id, movie_name, certification, description, director,
                           duration_minutes, ticket_price
                    FROM movies
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                List<Movie> movies = new ArrayList<>();
                while (rs.next()) {
                    movies.add(map(rs));
                }
                return movies;
            }
        });
    }

    // Helper method to map a database result set row to a Movie object
    private Movie map(ResultSet rs) throws SQLException {
        Movie movie = new Movie();
        movie.setMovieId(rs.getLong("movie_id"));
        movie.setMovieName(rs.getString("movie_name"));
        movie.setCertification(rs.getString("certification"));
        movie.setDescription(rs.getString("description"));
        movie.setDirector(rs.getString("director"));
        movie.setDurationInMinutes(rs.getInt("duration_minutes"));
        movie.setTicketPrice(rs.getInt("ticket_price"));
        return movie;
    }
}
