package service;

import dto.response.MovieDetailsResponse;
import dto.response.MovieResponse;
import dto.response.TheatreResponse;
import exception.NotFoundException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import model.Movie;
import model.Theatre;
import repository.MovieRepository;
import repository.TheatreRepository;

import java.util.List;
import java.util.logging.Logger;
import util.RequestLogContext;

@Singleton
public class MovieService {

    private static final Logger LOGGER = Logger.getLogger(MovieService.class.getName());

    private final MovieRepository movieRepository;
    private final TheatreRepository theatreRepository;

    @Inject
    public MovieService(MovieRepository movieRepository, TheatreRepository theatreRepository) {
        this.movieRepository = movieRepository;
        this.theatreRepository = theatreRepository;
    }

    public List<MovieResponse> browseMovies() {
        List<MovieResponse> movies = movieRepository.findAll().stream()
                .map(movie -> this.toMovieResponse(movie))
                .toList();
        LOGGER.info("event=movie.listed requestId=" + RequestLogContext.requestId()
            + " count=" + movies.size());
        return movies;
    }

    public MovieDetailsResponse getMovieDetails(Long movieId) {
        Movie movie = getMovie(movieId);
        MovieDetailsResponse response = toMovieDetailsResponse(movie);
        LOGGER.info("event=movie.viewed requestId=" + RequestLogContext.requestId()
            + " movieId=" + movieId);
        return response;
    }

    public List<TheatreResponse> getTheatresShowingMovie(Long movieId) {
        getMovie(movieId);
        List<TheatreResponse> theatres = theatreRepository.findByMovieId(movieId).stream()
                .map(theatre -> this.toTheatreResponse(theatre))
                .toList();
        LOGGER.info("event=movie.theatres.listed requestId=" + RequestLogContext.requestId()
            + " movieId=" + movieId + " count=" + theatres.size());
        return theatres;
    }

    public Movie getMovie(Long movieId) {
        return movieRepository.findById(movieId)
                .orElseThrow(() -> new NotFoundException("Movie not found"));
    }

    private MovieResponse toMovieResponse(Movie movie) {
        MovieResponse response = new MovieResponse();
        response.setMovieId(movie.getMovieId());
        response.setMovieName(movie.getMovieName());
        response.setCertification(movie.getCertification());
        response.setDurationInMinutes(movie.getDurationInMinutes());
        return response;
    }

    private MovieDetailsResponse toMovieDetailsResponse(Movie movie) {
        MovieDetailsResponse response = new MovieDetailsResponse();
        response.setMovieId(movie.getMovieId());
        response.setMovieName(movie.getMovieName());
        response.setCertification(movie.getCertification());
        response.setDescription(movie.getDescription());
        response.setDirector(movie.getDirector());
        response.setDurationInMinutes(movie.getDurationInMinutes());
        response.setTicketPrice(movie.getTicketPrice());
        return response;
    }

    private TheatreResponse toTheatreResponse(Theatre theatre) {
        TheatreResponse response = new TheatreResponse();
        response.setTheatreId(theatre.getTheatreId());
        response.setTheatreName(theatre.getTheatreName());
        response.setTheatreLocation(theatre.getTheatreLocation());
        return response;
    }
}
