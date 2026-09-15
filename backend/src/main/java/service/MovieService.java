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

@Singleton
public class MovieService {

    private final MovieRepository movieRepository;
    private final TheatreRepository theatreRepository;

    @Inject
    public MovieService(MovieRepository movieRepository, TheatreRepository theatreRepository) {
        this.movieRepository = movieRepository;
        this.theatreRepository = theatreRepository;
    }

    public List<MovieResponse> browseMovies() {
        return movieRepository.findAll().stream()
                .map(movie -> this.toMovieResponse(movie))
                .toList();
    }

    public MovieDetailsResponse getMovieDetails(Long movieId) {
        Movie movie = getMovie(movieId);
        return toMovieDetailsResponse(movie);
    }

    public List<TheatreResponse> getTheatresShowingMovie(Long movieId) {
        getMovie(movieId);
        return theatreRepository.findByMovieId(movieId).stream()
                .map(theatre -> this.toTheatreResponse(theatre))
                .toList();
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
