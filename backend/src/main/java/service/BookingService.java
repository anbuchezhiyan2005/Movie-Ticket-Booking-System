package service;

import config.Database;
import dto.request.BookingRequest;
import dto.request.SeatRequest;
import dto.response.BookingResponse;
import dto.response.SeatResponse;
import enums.BookingStatus;
import enums.Role;
import exception.ConflictException;
import exception.ForbiddenException;
import exception.NotFoundException;
import exception.ValidationException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import model.Booking;
import model.Movie;
import model.Screen;
import model.Show;
import model.ShowSeat;
import model.Theatre;
import model.User;
import repository.BookingRepository;
import repository.MovieRepository;
import repository.ScreenRepository;
import repository.ShowRepository;
import repository.ShowSeatRepository;
import repository.TheatreRepository;
import repository.UserRepository;
import util.ShowTimes;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Singleton
public class BookingService {

    private static final int PENDING_MINUTES = 5;
    private static final int FULL_REFUND_MINUTES_BEFORE_START = 30;

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final ShowRepository showRepository;
    private final MovieRepository movieRepository;
    private final ScreenRepository screenRepository;
    private final TheatreRepository theatreRepository;
    private final ShowSeatRepository showSeatRepository;
    private final PaymentService paymentService;

    @Inject
    public BookingService(
            BookingRepository bookingRepository,
            UserRepository userRepository,
            ShowRepository showRepository,
            MovieRepository movieRepository,
            ScreenRepository screenRepository,
            TheatreRepository theatreRepository,
            ShowSeatRepository showSeatRepository,
            PaymentService paymentService) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.showRepository = showRepository;
        this.movieRepository = movieRepository;
        this.screenRepository = screenRepository;
        this.theatreRepository = theatreRepository;
        this.showSeatRepository = showSeatRepository;
        this.paymentService = paymentService;
    }

    public BookingResponse bookTickets(Long customerId, BookingRequest request) throws SQLException {
        return Database.inTransaction(() -> bookTicketsInternal(customerId, request));
    }

    public List<BookingResponse> getMyBookings(Long customerId) {
        getUser(customerId);
        return bookingRepository.findByUserId(customerId).stream()
                .map(this::toBookingResponse)
                .collect(Collectors.toList());
    }

    public BookingResponse getBooking(Long bookingId, Long customerId) {
        Booking booking = getBookingEntity(bookingId);
        if (!booking.getUserId().equals(customerId)) {
            throw new ForbiddenException("You are not allowed to access this booking");
        }
        return toBookingResponse(booking);
    }

    public void cancelBooking(Long bookingId, Long customerId) throws SQLException {
        Database.inTransaction(() -> {
            cancelInternal(bookingId, customerId);
            return null;
        });
    }

    public List<SeatResponse> getAvailableSeats(Long showId) {
        Show show = getShow(showId);
        Screen screen = getScreen(show.getScreenId());

        List<ShowSeat> occupiedSeats = showSeatRepository.findByShowId(showId);
        Set<String> occupied = new HashSet<>();
        for (ShowSeat seat : occupiedSeats) {
            occupied.add(seat.getRowLabel() + "-" + seat.getSeatNumber());
        }

        List<SeatResponse> result = new ArrayList<>();
        List<String> rows = ShowTimes.generateRows(screen.getRowRange());
        for (String row : rows) {
            for (int seatNumber = 1; seatNumber <= screen.getSeatsPerRow(); seatNumber++) {
                SeatResponse seat = new SeatResponse();
                seat.setRowLabel(row);
                seat.setSeatNumber(seatNumber);
                seat.setAvailable(!occupied.contains(row + "-" + seatNumber));
                result.add(seat);
            }
        }
        return result;
    }

    public void expirePendingBookings() throws SQLException {
        Database.inTransaction(() -> {
            List<Booking> expired = bookingRepository.findExpiredPending(LocalDateTime.now());
            for (Booking booking : expired) {
                showSeatRepository.deleteByBookingId(booking.getBookingId());
                booking.setStatus(BookingStatus.EXPIRED);
                booking.setExpiresAt(null);
                bookingRepository.update(booking);
            }
            return null;
        });
    }

    private BookingResponse bookTicketsInternal(Long customerId, BookingRequest request) {
        validateBookingRequest(request);

        User customer = getUser(customerId);
        if (customer.getRole() != Role.CUSTOMER) {
            throw new ForbiddenException("Only customers can create bookings");
        }

        Show show = getShow(request.getShowId());
        if (!show.getShowTiming().isAfter(LocalDateTime.now())) {
            throw new ValidationException("Cannot book a show that has already started");
        }

        Screen screen = getScreen(show.getScreenId());
        Movie movie = getMovie(show.getMovieId());
        validateSeats(request.getSeats(), screen);
        validateDuplicateSeats(request.getSeats());

        Theatre theatre = getTheatre(screen.getTheatreId());
        Long adminId = theatre.getAdminId();
        int totalAmount = movie.getTicketPrice() * request.getSeats().size();

        LocalDateTime now = LocalDateTime.now();
        Booking booking = new Booking();
        booking.setUserId(customerId);
        booking.setShowId(show.getShowId());
        booking.setStatus(BookingStatus.PENDING);
        booking.setTotalAmount(totalAmount);
        booking.setBookingTime(now);
        booking.setExpiresAt(now.plusMinutes(PENDING_MINUTES));
        booking = bookingRepository.save(booking);

        for (SeatRequest seat : request.getSeats()) {
            ShowSeat showSeat = new ShowSeat();
            showSeat.setShowId(show.getShowId());
            showSeat.setRowLabel(seat.getRowLabel().trim().toUpperCase());
            showSeat.setSeatNumber(seat.getSeatNumber());
            showSeat.setBookingId(booking.getBookingId());

            boolean claimed = showSeatRepository.claimSeat(showSeat);
            if (!claimed) {
                throw new ConflictException(
                        "Seat " + seat.getRowLabel() + seat.getSeatNumber() + " is no longer available");
            }
        }

        paymentService.processPayment(customerId, adminId, totalAmount);

        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setExpiresAt(null);
        bookingRepository.update(booking);

        return toBookingResponse(booking, request.getSeats());
    }

    private void cancelInternal(Long bookingId, Long customerId) {
        Booking booking = getBookingEntity(bookingId);
        if (!booking.getUserId().equals(customerId)) {
            throw new ForbiddenException("You are not allowed to cancel this booking");
        }
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new ValidationException("Only confirmed bookings can be cancelled");
        }

        Show show = getShow(booking.getShowId());
        LocalDateTime now = LocalDateTime.now();
        if (ShowTimes.hasStarted(show.getShowTiming(), now)) {
            throw new ValidationException("Cannot cancel a booking after the show has started");
        }

        Screen screen = getScreen(show.getScreenId());
        Theatre theatre = getTheatre(screen.getTheatreId());

        int refundAmount = refundAmount(booking.getTotalAmount(), show.getShowTiming(), now);
        paymentService.refundPayment(customerId, theatre.getAdminId(), refundAmount);

        showSeatRepository.deleteByBookingId(bookingId);
        booking.setStatus(BookingStatus.CANCELLED);
        bookingRepository.update(booking);
    }

    static int refundAmount(int total, LocalDateTime startTime, LocalDateTime now) {
        if (now.isBefore(startTime.minusMinutes(FULL_REFUND_MINUTES_BEFORE_START))) {
            return total;
        }
        int kept = total / 4;
        return total - kept;
    }

    private void validateBookingRequest(BookingRequest request) {
        if (request == null) {
            throw new ValidationException("Booking request cannot be null");
        }
        if (request.getShowId() == null) {
            throw new ValidationException("Show ID cannot be null");
        }
        if (request.getSeats() == null || request.getSeats().isEmpty()) {
            throw new ValidationException("At least one seat must be selected");
        }
    }

    private void validateSeats(List<SeatRequest> seats, Screen screen) {
        List<String> validRows = ShowTimes.generateRows(screen.getRowRange());
        for (SeatRequest seat : seats) {
            if (seat.getRowLabel() == null || !validRows.contains(seat.getRowLabel().trim().toUpperCase())) {
                throw new ValidationException("Invalid row: " + seat.getRowLabel());
            }
            seat.setRowLabel(seat.getRowLabel().trim().toUpperCase());
            if (seat.getSeatNumber() <= 0 || seat.getSeatNumber() > screen.getSeatsPerRow()) {
                throw new ValidationException("Invalid seat number: " + seat.getSeatNumber());
            }
        }
    }

    private void validateDuplicateSeats(List<SeatRequest> seats) {
        Set<String> selected = new HashSet<>();
        for (SeatRequest seat : seats) {
            String key = seat.getRowLabel() + "-" + seat.getSeatNumber();
            if (!selected.add(key)) {
                throw new ValidationException("Duplicate seat selected: " + key);
            }
        }
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    private Show getShow(Long showId) {
        return showRepository.findById(showId)
                .orElseThrow(() -> new NotFoundException("Show not found"));
    }

    private Screen getScreen(Long screenId) {
        return screenRepository.findById(screenId)
                .orElseThrow(() -> new NotFoundException("Screen not found"));
    }

    private Movie getMovie(Long movieId) {
        return movieRepository.findById(movieId)
                .orElseThrow(() -> new NotFoundException("Movie not found"));
    }

    private Theatre getTheatre(Long theatreId) {
        return theatreRepository.findById(theatreId)
                .orElseThrow(() -> new NotFoundException("Theatre not found"));
    }

    private Booking getBookingEntity(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking not found"));
    }

    private BookingResponse toBookingResponse(Booking booking) {
        List<SeatResponse> seats = showSeatRepository.findByBookingId(booking.getBookingId()).stream()
                .map(this::toSeatResponse)
                .collect(Collectors.toList());
        return buildBookingResponse(booking, seats);
    }

    private BookingResponse toBookingResponse(Booking booking, List<SeatRequest> requestedSeats) {
        List<SeatResponse> seats = requestedSeats.stream()
                .map(this::toSeatResponse)
                .collect(Collectors.toList());
        return buildBookingResponse(booking, seats);
    }

    private BookingResponse buildBookingResponse(Booking booking, List<SeatResponse> seats) {
        BookingResponse response = new BookingResponse();
        response.setBookingId(booking.getBookingId());
        response.setShowId(booking.getShowId());
        response.setStatus(booking.getStatus());
        response.setTotalAmount(booking.getTotalAmount());
        response.setBookingTime(booking.getBookingTime());
        response.setSeats(seats);
        return response;
    }

    private SeatResponse toSeatResponse(ShowSeat seat) {
        SeatResponse response = new SeatResponse();
        response.setRowLabel(seat.getRowLabel());
        response.setSeatNumber(seat.getSeatNumber());
        response.setAvailable(false);
        return response;
    }

    private SeatResponse toSeatResponse(SeatRequest seat) {
        SeatResponse response = new SeatResponse();
        response.setRowLabel(seat.getRowLabel());
        response.setSeatNumber(seat.getSeatNumber());
        response.setAvailable(false);
        return response;
    }
}
