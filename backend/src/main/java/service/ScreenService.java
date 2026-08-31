package service;

import dto.request.ScreenRequest;
import dto.response.ScreenResponse;
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
import repository.MovieRepository;
import repository.ScreenRepository;
import repository.BookingRepository;
import repository.ShowRepository;
import repository.TheatreRepository;
import util.ShowTimes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Singleton
public class ScreenService {

    private final ScreenRepository screenRepository;
    private final TheatreRepository theatreRepository;
    private final ShowRepository showRepository;
    private final MovieRepository movieRepository;
    private final BookingRepository bookingRepository;

    @Inject
    public ScreenService(
            ScreenRepository screenRepository,
            TheatreRepository theatreRepository,
            ShowRepository showRepository,
            MovieRepository movieRepository,
            BookingRepository bookingRepository) {
        this.screenRepository = screenRepository;
        this.theatreRepository = theatreRepository;
        this.showRepository = showRepository;
        this.movieRepository = movieRepository;
        this.bookingRepository = bookingRepository;
    }

    public Screen createScreen(ScreenRequest request, Long adminId) {
        validateRequest(request);
        Theatre theatre = getTheatre(request.getTheatreId());
        verifyOwnership(theatre, adminId);

        Screen screen = new Screen();
        screen.setTheatreId(theatre.getTheatreId());
        screen.setScreenName(request.getScreenName().trim());
        screen.setRowRange(request.getRowRange().trim().toUpperCase());
        screen.setSeatsPerRow(request.getSeatsPerRow());
        ShowTimes.generateRows(screen.getRowRange());
        return screenRepository.save(screen);
    }

    public void updateScreen(Long screenId, ScreenRequest request, Long adminId) {
        validateRequest(request);
        Screen screen = getScreen(screenId);
        Theatre theatre = getTheatre(screen.getTheatreId());
        verifyOwnership(theatre, adminId);
        if (bookingRepository.existsByScreenId(screenId)) {
            throw new ConflictException("Cannot update a screen that has bookings");
        }
        requireAllShowsEnded(screenId);

        screen.setScreenName(request.getScreenName().trim());
        screen.setRowRange(request.getRowRange().trim().toUpperCase());
        screen.setSeatsPerRow(request.getSeatsPerRow());
        ShowTimes.generateRows(screen.getRowRange());
        screenRepository.update(screen);
    }

    public void deleteScreen(Long screenId, Long adminId) {
        Screen screen = getScreen(screenId);
        Theatre theatre = getTheatre(screen.getTheatreId());
        verifyOwnership(theatre, adminId);
        requireAllShowsEnded(screenId);

        List<Show> shows = showRepository.findByScreenId(screenId);
        if (!shows.isEmpty()) {
            throw new ConflictException("Delete ended shows on this screen before deleting the screen");
        }
        screenRepository.deleteById(screenId);
    }

    public List<ScreenResponse> getScreensForTheatre(Long theatreId, Long adminId) {
        Theatre theatre = getTheatre(theatreId);
        verifyOwnership(theatre, adminId);
        return screenRepository.findByTheatreId(theatreId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public ScreenResponse toResponse(Screen screen) {
        ScreenResponse response = new ScreenResponse();
        response.setScreenId(screen.getScreenId());
        response.setTheatreId(screen.getTheatreId());
        response.setScreenName(screen.getScreenName());
        response.setRowRange(screen.getRowRange());
        response.setSeatsPerRow(screen.getSeatsPerRow());
        return response;
    }

    private void requireAllShowsEnded(Long screenId) {
        LocalDateTime now = LocalDateTime.now();
        for (Show show : showRepository.findByScreenId(screenId)) {
            Movie movie = movieRepository.findById(show.getMovieId())
                    .orElseThrow(() -> new NotFoundException("Movie not found"));
            if (!ShowTimes.hasEnded(show.getShowTiming(), movie.getDurationInMinutes(), now)) {
                throw new ConflictException("Cannot change screen until all of its shows have ended");
            }
        }
    }

    private Screen getScreen(Long screenId) {
        return screenRepository.findById(screenId)
                .orElseThrow(() -> new NotFoundException("Screen not found"));
    }

    private Theatre getTheatre(Long theatreId) {
        return theatreRepository.findById(theatreId)
                .orElseThrow(() -> new NotFoundException("Theatre not found"));
    }

    private void verifyOwnership(Theatre theatre, Long adminId) {
        if (!theatre.getAdminId().equals(adminId)) {
            throw new ForbiddenException("Admin does not own this theatre");
        }
    }

    private void validateRequest(ScreenRequest request) {
        if (request == null) {
            throw new ValidationException("Screen request cannot be null");
        }
        if (request.getTheatreId() == null) {
            throw new ValidationException("Theatre ID cannot be null");
        }
        if (request.getScreenName() == null || request.getScreenName().isBlank()) {
            throw new ValidationException("Screen name cannot be empty");
        }
        if (request.getRowRange() == null || request.getRowRange().isBlank()) {
            throw new ValidationException("Row range cannot be empty");
        }
        if (request.getSeatsPerRow() <= 0) {
            throw new ValidationException("Seats per row must be greater than zero");
        }
    }
}
