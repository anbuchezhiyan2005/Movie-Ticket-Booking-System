package service;

import cache.CachedShowSeats;
import cache.SeatAvailabilityCache;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Singleton
public class BookingService {

    private static final Logger LOGGER = Logger.getLogger(BookingService.class.getName());
    private static final int PENDING_MINUTES = 5;
    private static final int FULL_REFUND_MINUTES_BEFORE_START = 30;
    private static final long PAYMENT_DELAY_MILLIS = Long.getLong("booking.payment.delay.ms", 15_000L);
    private final ConcurrentHashMap<String, Object> activeBookingLocks = new ConcurrentHashMap<>();

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final ShowRepository showRepository;
    private final MovieRepository movieRepository;
    private final ScreenRepository screenRepository;
    private final TheatreRepository theatreRepository;
    private final ShowSeatRepository showSeatRepository;
    private final PaymentService paymentService;
    private final SeatAvailabilityCache seatAvailabilityCache;
    private final ConfirmationEmailService confirmationEmailService;

    @Inject
    public BookingService(
            BookingRepository bookingRepository,
            UserRepository userRepository,
            ShowRepository showRepository,
            MovieRepository movieRepository,
            ScreenRepository screenRepository,
            TheatreRepository theatreRepository,
            ShowSeatRepository showSeatRepository,
            PaymentService paymentService,
            SeatAvailabilityCache seatAvailabilityCache,
            ConfirmationEmailService confirmationEmailService) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.showRepository = showRepository;
        this.movieRepository = movieRepository;
        this.screenRepository = screenRepository;
        this.theatreRepository = theatreRepository;
        this.showSeatRepository = showSeatRepository;
        this.paymentService = paymentService;
        this.seatAvailabilityCache = seatAvailabilityCache;
        this.confirmationEmailService = confirmationEmailService;
    }

    public BookingResponse bookTickets(Long customerId, BookingRequest request) throws SQLException {
        validateBookingRequest(request);
        List<String> lockKeys = bookingLockKeys(request);
        Map<String, Object> acquiredLocks = new LinkedHashMap<>();

        try {
            for (String lockKey : lockKeys) {
                Object lockToken = new Object();
                if (activeBookingLocks.putIfAbsent(lockKey, lockToken) != null) {
                    throw new ConflictException("One of the selected seats is currently being booked");
                }
                acquiredLocks.put(lockKey, lockToken);
            }
            BookingResult result = Database.inTransaction(() -> bookTicketsInternal(customerId, request));
            try {
                confirmationEmailService.queueConfirmation(result.confirmationEmail());
            } catch (RuntimeException error) {
                LOGGER.log(Level.SEVERE, "Unable to queue booking confirmation email for booking "
                        + result.response().getBookingId(), error);
            }
            return result.response();
        } finally {
            acquiredLocks.forEach((lockKey, lockToken) -> activeBookingLocks.remove(lockKey, lockToken));
        }
    }

    private List<String> bookingLockKeys(BookingRequest request) {
        return request.getSeats().stream()
                .map(seat -> request.getShowId() + ":"
                        + (seat.getRowLabel() == null ? "" : seat.getRowLabel().trim().toUpperCase())
                        + "-" + seat.getSeatNumber())
                .distinct()
                .sorted()
                .toList();
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

        // Try to get from cache first
        CachedShowSeats cached = seatAvailabilityCache.get(showId);
        java.util.Map<String, CachedShowSeats.SeatStatus> occupiedMap;

        if (cached != null) {
            // Cache hit: use cached occupancy
            occupiedMap = cached.getOccupiedSeats();
        } else {
            // Cache miss: query DB and populate cache
            java.util.Map<String, String> seatStatuses = showSeatRepository.findStatusByShowId(showId);
            occupiedMap = new java.util.HashMap<>();
            for (java.util.Map.Entry<String, String> entry : seatStatuses.entrySet()) {
                CachedShowSeats.SeatStatus status = "PENDING".equals(entry.getValue())
                        ? CachedShowSeats.SeatStatus.HELD
                        : CachedShowSeats.SeatStatus.BOOKED;
                occupiedMap.put(entry.getKey(), status);
            }
            cached = seatAvailabilityCache.put(showId, new CachedShowSeats(showId, occupiedMap));
            occupiedMap = cached.getOccupiedSeats();
        }

        // Generate seat grid
        List<SeatResponse> result = new ArrayList<>();
        List<String> rows = ShowTimes.generateRows(screen.getRowRange());
        for (String row : rows) {
            for (int seatNumber = 1; seatNumber <= screen.getSeatsPerRow(); seatNumber++) {
                String seatKey = row + "-" + seatNumber;
                SeatResponse seat = new SeatResponse();
                seat.setRowLabel(row);
                seat.setSeatNumber(seatNumber);

                CachedShowSeats.SeatStatus status = occupiedMap.get(seatKey);
                if (status == null) {
                    seat.setAvailable(true);
                    seat.setStatus("AVAILABLE");
                } else if (status == CachedShowSeats.SeatStatus.HELD) {
                    seat.setAvailable(false);
                    seat.setStatus("HELD");
                } else { // BOOKED
                    seat.setAvailable(false);
                    seat.setStatus("BOOKED");
                }
                result.add(seat);
            }
        }
        return result;
    }

    public void expirePendingBookings() throws SQLException {
        Database.inTransaction(() -> {
            List<Booking> expired = bookingRepository.findExpiredPending(LocalDateTime.now());
            for (Booking booking : expired) {
                List<ShowSeat> expiredSeats = showSeatRepository.findByBookingId(booking.getBookingId());
                showSeatRepository.deleteByBookingId(booking.getBookingId());
                booking.setStatus(BookingStatus.EXPIRED);
                booking.setExpiresAt(null);
                bookingRepository.update(booking);

                // Surgical cache update: remove expired seats
                for (ShowSeat seat : expiredSeats) {
                    String seatKey = seat.getRowLabel() + "-" + seat.getSeatNumber();
                    seatAvailabilityCache.removeSeat(booking.getShowId(), seatKey);
                }
            }
            return null;
        });
    }

    private BookingResult bookTicketsInternal(Long customerId, BookingRequest request) {
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
        final int totalAmount;
        try {
            totalAmount = Math.multiplyExact(movie.getTicketPrice(), request.getSeats().size());
        } catch (ArithmeticException e) {
            throw new ValidationException("Booking total is too large");
        }

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

        for (SeatRequest seat : request.getSeats()) {
            String seatKey = seat.getRowLabel() + "-" + seat.getSeatNumber();
            seatAvailabilityCache.addSeat(show.getShowId(), seatKey,
                                         CachedShowSeats.SeatStatus.HELD);
        }

        try {
            if (PAYMENT_DELAY_MILLIS > 0) {
                Thread.sleep(PAYMENT_DELAY_MILLIS);
            }
            paymentService.processPayment(customerId, adminId, totalAmount);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            for (SeatRequest seat : request.getSeats()) {
                String seatKey = seat.getRowLabel() + "-" + seat.getSeatNumber();
                seatAvailabilityCache.removeSeat(show.getShowId(), seatKey);
            }
            throw new IllegalStateException("Booking payment was interrupted", error);
        } catch (RuntimeException error) {
            for (SeatRequest seat : request.getSeats()) {
                String seatKey = seat.getRowLabel() + "-" + seat.getSeatNumber();
                seatAvailabilityCache.removeSeat(show.getShowId(), seatKey);
            }
            throw error;
        }

        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setExpiresAt(null);
        bookingRepository.update(booking);

        // Surgical cache update: mark seats as BOOKED
        for (SeatRequest seat : request.getSeats()) {
            String seatKey = seat.getRowLabel() + "-" + seat.getSeatNumber();
            seatAvailabilityCache.addSeat(show.getShowId(), seatKey, 
                                         CachedShowSeats.SeatStatus.BOOKED);
        }

        BookingResponse response = toBookingResponse(booking, request.getSeats());
        ConfirmationEmail confirmationEmail = new ConfirmationEmail(
            customer.getEmail(),
            customer.getName(),
            booking.getBookingId(),
            movie.getMovieName(),
            theatre.getTheatreName(),
            theatre.getTheatreLocation(),
            show.getShowTiming(),
            request.getSeats().stream()
                .map(seat -> seat.getRowLabel() + "-" + seat.getSeatNumber())
                .toList(),
            booking.getTotalAmount());
        return new BookingResult(response, confirmationEmail);
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

        List<ShowSeat> cancelledSeats = showSeatRepository.findByBookingId(bookingId);
        showSeatRepository.deleteByBookingId(bookingId);
        booking.setStatus(BookingStatus.CANCELLED);
        bookingRepository.update(booking);

        // Surgical cache update: remove seats from cache
        for (ShowSeat seat : cancelledSeats) {
            String seatKey = seat.getRowLabel() + "-" + seat.getSeatNumber();
            seatAvailabilityCache.removeSeat(booking.getShowId(), seatKey);
        }
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

    private record BookingResult(BookingResponse response, ConfirmationEmail confirmationEmail) {
    }
}
