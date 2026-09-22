package service;

import dto.request.ShowRequest;
import dto.response.ShowResponse;
import exception.ConflictException;
import exception.ForbiddenException;
import exception.NotFoundException;
import exception.ValidationException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import model.Movie;
import model.Screen;
import model.Show;
import model.Theatre;
import repository.BookingRepository;
import repository.MovieRepository;
import repository.ScreenRepository;
import repository.ShowRepository;
import repository.TheatreRepository;
import util.ShowTimes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.logging.Logger;
import util.RequestLogContext;

@Singleton
public class ShowService {

    private static final Logger LOGGER = Logger.getLogger(ShowService.class.getName());

    private final ShowRepository showRepository;
    private final MovieRepository movieRepository;
    private final ScreenRepository screenRepository;
    private final TheatreRepository theatreRepository;
    private final BookingRepository bookingRepository;

    @Inject
    public ShowService(
            ShowRepository showRepository,
            MovieRepository movieRepository,
            ScreenRepository screenRepository,
            TheatreRepository theatreRepository,
            BookingRepository bookingRepository) {
        this.showRepository = showRepository;
        this.movieRepository = movieRepository;
        this.screenRepository = screenRepository;
        this.theatreRepository = theatreRepository;
        this.bookingRepository = bookingRepository;
    }

    public Show createShow(ShowRequest request, Long adminId) {
        validateRequest(request);

        Movie movie = getMovie(request.getMovieId());
        Screen screen = getScreen(request.getScreenId());
        Theatre theatre = getTheatre(screen.getTheatreId());
        verifyOwnership(theatre, adminId);

        if (!request.getShowTiming().isAfter(LocalDateTime.now())) {
            throw new ValidationException("Show timing must be in the future");
        }

        ensureNoOverlap(screen.getScreenId(), request.getShowTiming(), movie.getDurationInMinutes(), null);

        Show show = new Show();
        show.setMovieId(movie.getMovieId());
        show.setScreenId(screen.getScreenId());
        show.setShowTiming(request.getShowTiming());
        Show saved = showRepository.save(show);
        LOGGER.info("event=show.created requestId=" + RequestLogContext.requestId()
            + " showId=" + saved.getShowId() + " movieId=" + saved.getMovieId()
            + " screenId=" + saved.getScreenId() + " adminId=" + adminId);
        return saved;
    }

    public void updateShow(Long showId, ShowRequest request, Long adminId) {
        validateRequest(request);

        Show show = getShow(showId);
        Screen existingScreen = getScreen(show.getScreenId());
        Theatre theatre = getTheatre(existingScreen.getTheatreId());
        verifyOwnership(theatre, adminId);

        if (bookingRepository.existsConfirmedBookingForShow(showId)) {
            throw new ConflictException("Cannot update a show with confirmed bookings");
        }

        if (ShowTimes.hasStarted(show.getShowTiming(), LocalDateTime.now())) {
            throw new ConflictException("Cannot update a show that has already started");
        }

        Movie movie = getMovie(request.getMovieId());
        Screen newScreen = getScreen(request.getScreenId());
        Theatre newTheatre = getTheatre(newScreen.getTheatreId());
        verifyOwnership(newTheatre, adminId);

        ensureNoOverlap(newScreen.getScreenId(), request.getShowTiming(), movie.getDurationInMinutes(), showId);

        show.setMovieId(movie.getMovieId());
        show.setScreenId(newScreen.getScreenId());
        show.setShowTiming(request.getShowTiming());
        showRepository.update(show);
        LOGGER.info("event=show.updated requestId=" + RequestLogContext.requestId()
            + " showId=" + showId + " adminId=" + adminId);
    }

    public void deleteShow(Long showId, Long adminId) {
        throw new ConflictException("Show deletion is temporarily disabled");
    }

    public List<ShowResponse> getShowsForMovie(Long movieId) {
        getMovie(movieId);
        List<ShowResponse> shows = showRepository.findByMovieId(movieId).stream()
                .map(show -> this.toShowResponse(show))
                .toList();
        LOGGER.info("event=show.listed requestId=" + RequestLogContext.requestId()
            + " movieId=" + movieId + " count=" + shows.size());
        return shows;
    }

    public List<ShowResponse> getShowsForScreen(Long screenId) {
        getScreen(screenId);
        List<ShowResponse> shows = showRepository.findByScreenId(screenId).stream()
                .map(show -> this.toShowResponse(show))
                .toList();
        LOGGER.info("event=show.listed requestId=" + RequestLogContext.requestId()
            + " screenId=" + screenId + " count=" + shows.size());
        return shows;
    }

    public List<ShowResponse> getShowsForTheatre(Long theatreId, Long adminId) {
        Theatre theatre = getTheatre(theatreId);
        verifyOwnership(theatre, adminId);

        List<ShowResponse> shows = screenRepository.findByTheatreId(theatreId).stream()
                .flatMap(screen -> showRepository.findByScreenId(screen.getScreenId()).stream())
                .filter(show -> this.hasNotEnded(show))
                .map(show -> this.toShowResponse(show))
                .toList();
        LOGGER.info("event=show.listed requestId=" + RequestLogContext.requestId()
            + " theatreId=" + theatreId + " adminId=" + adminId + " count=" + shows.size());
        return shows;
    }

    public List<ShowResponse> getShowsForScreen(Long screenId, Long adminId) {
        Screen screen = getScreen(screenId);
        Theatre theatre = getTheatre(screen.getTheatreId());
        verifyOwnership(theatre, adminId);

        List<ShowResponse> shows = showRepository.findByScreenId(screenId).stream()
                .filter(show -> this.hasNotEnded(show))
                .map(show -> this.toShowResponse(show))
                .toList();
        LOGGER.info("event=show.listed requestId=" + RequestLogContext.requestId()
            + " screenId=" + screenId + " adminId=" + adminId + " count=" + shows.size());
        return shows;
    }

    private boolean hasNotEnded(Show show) {
        Movie movie = getMovie(show.getMovieId());
        return !ShowTimes.hasEnded(show.getShowTiming(), movie.getDurationInMinutes(), LocalDateTime.now());
    }

    public ShowResponse toShowResponse(Show show) {
        ShowResponse response = new ShowResponse();
        response.setShowId(show.getShowId());
        response.setMovieId(show.getMovieId());
        response.setScreenId(show.getScreenId());
        response.setShowTiming(show.getShowTiming());
        return response;
    }

    private void ensureNoOverlap(Long screenId, LocalDateTime start, int durationMinutes, Long ignoreShowId) {
        for (Show existing : showRepository.findByScreenId(screenId)) {
            if (ignoreShowId != null && existing.getShowId().equals(ignoreShowId)) {
                continue;
            }
            Movie otherMovie = getMovie(existing.getMovieId());
            if (ShowTimes.overlaps(
                    start,
                    durationMinutes,
                    existing.getShowTiming(),
                    otherMovie.getDurationInMinutes())) {
                throw new ConflictException("Show timing overlaps another show on this screen");
            }
        }
    }

    private void validateRequest(ShowRequest request) {
        if (request == null) {
            throw new ValidationException("Show request cannot be null");
        }
        if (request.getMovieId() == null) {
            throw new ValidationException("Movie ID cannot be null");
        }
        if (request.getScreenId() == null) {
            throw new ValidationException("Screen ID cannot be null");
        }
        if (request.getShowTiming() == null) {
            throw new ValidationException("Show timing cannot be null");
        }
    }

    private Movie getMovie(Long movieId) {
        return movieRepository.findById(movieId)
                .orElseThrow(() -> new NotFoundException("Movie not found"));
    }

    private Screen getScreen(Long screenId) {
        return screenRepository.findById(screenId)
                .orElseThrow(() -> new NotFoundException("Screen not found"));
    }

    private Theatre getTheatre(Long theatreId) {
        return theatreRepository.findById(theatreId)
                .orElseThrow(() -> new NotFoundException("Theatre not found"));
    }

    private Show getShow(Long showId) {
        return showRepository.findById(showId)
                .orElseThrow(() -> new NotFoundException("Show not found"));
    }

    private void verifyOwnership(Theatre theatre, Long adminId) {
        if (!theatre.getAdminId().equals(adminId)) {
            throw new ForbiddenException("Admin does not own this theatre");
        }
    }
}
