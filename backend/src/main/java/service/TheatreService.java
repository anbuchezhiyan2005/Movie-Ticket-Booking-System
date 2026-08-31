package service;

import dto.request.TheatreRequest;
import dto.response.TheatreResponse;
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
import repository.ShowRepository;
import repository.TheatreRepository;
import repository.BookingRepository;
import util.ShowTimes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Singleton
public class TheatreService {

    private final TheatreRepository theatreRepository;
    private final ScreenRepository screenRepository;
    private final ShowRepository showRepository;
    private final MovieRepository movieRepository;
    private final BookingRepository bookingRepository;

    @Inject
    public TheatreService(
            TheatreRepository theatreRepository,
            ScreenRepository screenRepository,
            ShowRepository showRepository,
            MovieRepository movieRepository,
            BookingRepository bookingRepository) {
        this.theatreRepository = theatreRepository;
        this.screenRepository = screenRepository;
        this.showRepository = showRepository;
        this.movieRepository = movieRepository;
        this.bookingRepository = bookingRepository;
    }

    public Theatre createTheatre(TheatreRequest request, Long adminId) {
        validateRequest(request);

        Theatre theatre = new Theatre();
        theatre.setTheatreName(request.getTheatreName().trim());
        theatre.setTheatreLocation(request.getTheatreLocation().trim());
        theatre.setAdminId(adminId);
        return theatreRepository.save(theatre);
    }

    public void updateTheatre(Long theatreId, TheatreRequest request, Long adminId) {
        validateRequest(request);
        Theatre theatre = getOwnedTheatre(theatreId, adminId);
        if (bookingRepository.existsByTheatreId(theatreId)) {
            throw new ConflictException("Cannot update a theatre that has bookings");
        }
        requireNoUnfinishedShows(theatreId);

        theatre.setTheatreName(request.getTheatreName().trim());
        theatre.setTheatreLocation(request.getTheatreLocation().trim());
        theatreRepository.update(theatre);
    }

    public void deleteTheatre(Long theatreId, Long adminId) {
        getOwnedTheatre(theatreId, adminId);
        requireNoUnfinishedShows(theatreId);

        List<Screen> screens = screenRepository.findByTheatreId(theatreId);
        if (!screens.isEmpty()) {
            throw new ConflictException("Delete screens (and their ended shows) before deleting the theatre");
        }
        theatreRepository.deleteById(theatreId);
    }

    public List<TheatreResponse> getMyTheatres(Long adminId) {
        return theatreRepository.findByAdminId(adminId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public TheatreResponse toResponse(Theatre theatre) {
        TheatreResponse response = new TheatreResponse();
        response.setTheatreId(theatre.getTheatreId());
        response.setTheatreName(theatre.getTheatreName());
        response.setTheatreLocation(theatre.getTheatreLocation());
        return response;
    }

    private Theatre getOwnedTheatre(Long theatreId, Long adminId) {
        Theatre theatre = theatreRepository.findById(theatreId)
                .orElseThrow(() -> new NotFoundException("Theatre not found"));
        if (!theatre.getAdminId().equals(adminId)) {
            throw new ForbiddenException("Admin does not own this theatre");
        }
        return theatre;
    }

    private void requireNoUnfinishedShows(Long theatreId) {
        LocalDateTime now = LocalDateTime.now();
        for (Screen screen : screenRepository.findByTheatreId(theatreId)) {
            for (Show show : showRepository.findByScreenId(screen.getScreenId())) {
                Movie movie = movieRepository.findById(show.getMovieId())
                        .orElseThrow(() -> new NotFoundException("Movie not found"));
                if (!ShowTimes.hasEnded(show.getShowTiming(), movie.getDurationInMinutes(), now)) {
                    throw new ConflictException("Cannot change theatre while it still has unfinished shows");
                }
            }
        }
    }

    private void validateRequest(TheatreRequest request) {
        if (request == null) {
            throw new ValidationException("Theatre request cannot be null");
        }
        if (request.getTheatreName() == null || request.getTheatreName().isBlank()) {
            throw new ValidationException("Theatre name cannot be empty");
        }
        if (request.getTheatreLocation() == null || request.getTheatreLocation().isBlank()) {
            throw new ValidationException("Theatre location cannot be empty");
        }
    }
}
