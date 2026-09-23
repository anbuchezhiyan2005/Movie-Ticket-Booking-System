package service;

import cache.SeatAvailabilityCache;
import config.Database;
import dto.request.BookingRequest;
import dto.request.SeatRequest;
import dto.response.BookingResponse;
import dto.response.SeatResponse;
import dto.response.SeatMapResponse;
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
import repository.BookingGateTokenRepository;
import repository.OtpChallengeRepository;
import repository.ScreenRepository;
import repository.ShowRepository;
import repository.ShowSeatRepository;
import repository.TheatreRepository;
import repository.UserRepository;
import util.ShowTimes;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Coordinates booking workflows and keeps booking state consistent across the
 * database, seat cache, wallet, OTP, email, and gate-ticket services.
 */
@Singleton
public class BookingService {

    private static final Logger LOGGER = Logger.getLogger(BookingService.class.getName());
    private static final int PENDING_MINUTES = 5;
    private static final int RECENT_BOOKINGS_LIMIT = 10;
    private static final int FULL_REFUND_MINUTES_BEFORE_START = 30;
    private final ConcurrentHashMap<String, Object> activeBookingLocks = new ConcurrentHashMap<>();

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final ShowRepository showRepository;
    private final ScreenRepository screenRepository;
    private final TheatreRepository theatreRepository;
    private final ShowSeatRepository showSeatRepository;
    private final OtpChallengeRepository otpChallengeRepository;
    private final PaymentService paymentService;
    private final SeatAvailabilityCache seatAvailabilityCache;
    private final ConfirmationEmailService confirmationEmailService;
    private final GateTokenService gateTokenService;
    private final OtpService otpService;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    @Inject
    public BookingService(
            BookingRepository bookingRepository,
            UserRepository userRepository,
            ShowRepository showRepository,
            ScreenRepository screenRepository,
            TheatreRepository theatreRepository,
            ShowSeatRepository showSeatRepository,
            OtpChallengeRepository otpChallengeRepository,
            PaymentService paymentService,
            SeatAvailabilityCache seatAvailabilityCache,
            ConfirmationEmailService confirmationEmailService,
            GateTokenService gateTokenService,
            OtpService otpService) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.showRepository = showRepository;
        this.screenRepository = screenRepository;
        this.theatreRepository = theatreRepository;
        this.showSeatRepository = showSeatRepository;
        this.otpChallengeRepository = otpChallengeRepository;
        this.paymentService = paymentService;
        this.seatAvailabilityCache = seatAvailabilityCache;
        this.confirmationEmailService = confirmationEmailService;
        this.gateTokenService = gateTokenService;
        this.otpService = otpService;
    }

    public BookingService(
            BookingRepository bookingRepository,
            UserRepository userRepository,
            ShowRepository showRepository,
            ScreenRepository screenRepository,
            TheatreRepository theatreRepository,
            ShowSeatRepository showSeatRepository,
            PaymentService paymentService,
            SeatAvailabilityCache seatAvailabilityCache,
            ConfirmationEmailService confirmationEmailService) {
        this(bookingRepository, userRepository, showRepository, screenRepository,
            theatreRepository, showSeatRepository, new OtpChallengeRepository(), paymentService,
            seatAvailabilityCache,
            confirmationEmailService, new GateTokenService(new BookingGateTokenRepository()),
            new OtpService(new OtpChallengeRepository()));
    }

    /**
     * Creates and immediately confirms a booking after claiming its seats and
     * charging the customer's wallet.
     */
    // -------------------------------------------------------------------------
    // Booking creation and confirmation
    // -------------------------------------------------------------------------

    /**
     * Creates a temporary seat hold. The booking remains awaiting OTP
     * verification until {@link #confirmHeldBookingWithOtp} succeeds.
     */
    public BookingResponse holdTickets(Long customerId, BookingRequest request) throws SQLException {
        validateBookingRequest(request);
        List<SeatRequest> seats = orderedSeats(request);
        List<String> lockKeys = bookingLockKeys(request.getShowId(), seats);
        Map<String, Object> acquiredLocks = new LinkedHashMap<>();
        try {
            for (String lockKey : lockKeys) {
                Object lockToken = new Object();
                // QUESTION: What happens when a seat lock fails?? How does the lock for previous seats get released??
                if (activeBookingLocks.putIfAbsent(lockKey, lockToken) != null) {
                        LOGGER.warning("event=booking.seat_conflict requestId=" + util.RequestLogContext.requestId()
                            + " userId=" + customerId + " showId=" + request.getShowId()
                            + " phase=holding");
                    throw new ConflictException("One of the selected seats is currently being booked");
                }
                acquiredLocks.put(lockKey, lockToken);
            }
            BookingResponse response = Database.inTransactionWithDeadlockRetry(
                    () -> holdTicketsInternal(customerId, request, seats));
            updateCache(response, "HELD");
                LOGGER.info("event=booking.hold.created requestId=" + util.RequestLogContext.requestId()
                    + " userId=" + customerId + " bookingId=" + response.getBookingId());
            return response;
        } finally {
            acquiredLocks.forEach((lockKey, lockToken) -> activeBookingLocks.remove(lockKey, lockToken));
        }
    }

    /** Confirms a temporary hold after validating its booking OTP. */
    public BookingResponse confirmHeldBookingWithOtp(Long bookingId, Long customerId,
                                                     String challengeToken, String code) throws SQLException {
        BookingResult result = Database.inTransaction(() -> {
            if (!otpService.verifyCodeInTransaction(challengeToken, customerId, bookingId,
                    enums.OtpPurpose.BOOKING, code)) {
                return null;
            }
            return confirmHeldBookingInternal(bookingId, customerId);
        });
        if (result == null) {
            throw new ValidationException("OTP is invalid");
        }
        updateCache(result.response(), "BOOKED");
        try {
            // WHAT HAPPENS HERE?? HOW IS THE EMAIL SENT??
            confirmationEmailService.queueConfirmation(result.confirmationEmail());
                LOGGER.info("event=booking.confirmation_email.queued requestId=" + util.RequestLogContext.requestId()
                    + " bookingId=" + bookingId);
        } catch (RuntimeException error) {
                LOGGER.log(Level.SEVERE, "event=system.error requestId=" + util.RequestLogContext.requestId()
                    + " location=booking.confirmation_email_queue bookingId=" + bookingId, error);
        }
        return result.response();
    }

    /** Releases a hold when OTP delivery fails before the customer can verify it. */
    public void expireBookingHold(Long bookingId, Long customerId) throws SQLException {
        List<ShowSeat> seats = Database.inTransaction(() -> {
            Booking booking = getBookingEntity(bookingId);
            if (!booking.getUserId().equals(customerId)) {
                throw new ForbiddenException("You are not allowed to release this booking");
            }
            List<ShowSeat> heldSeats = List.of();
            if (booking.getStatus() == BookingStatus.AWAITING_OTP) {
                heldSeats = showSeatRepository.findByBookingId(bookingId);
                showSeatRepository.deleteByBookingId(bookingId);
                booking.setStatus(BookingStatus.EXPIRED);
                booking.setExpiresAt(null);
                bookingRepository.update(booking);
                LOGGER.info("event=booking.hold.expired requestId=" + util.RequestLogContext.requestId()
                    + " bookingId=" + bookingId + " reason=email_delivery_failed");
            }
            return heldSeats;
        });
        removeFromCache(seats);
    }

    private List<String> bookingLockKeys(Long showId, List<SeatRequest> seats) {
        return seats.stream()
            .map(seat -> showId + ":"
                + seat.getRowLabel() + "-" + seat.getSeatNumber())
            .distinct()
            .toList();
    }

    private List<SeatRequest> orderedSeats(BookingRequest request) {
        return request.getSeats().stream()
            .map(seat -> {
                SeatRequest normalized = new SeatRequest();
                normalized.setRowLabel(seat.getRowLabel() == null
                    ? ""
                    : seat.getRowLabel().trim().toUpperCase());
                normalized.setSeatNumber(seat.getSeatNumber());
                return normalized;
            })
            .sorted(Comparator.comparing(SeatRequest::getRowLabel)
                .thenComparingInt(SeatRequest::getSeatNumber))
                .toList();
            }

    /** Returns the customer's ten most recent bookings with their selected seats. */
    // -------------------------------------------------------------------------
    // Booking retrieval and cancellation
    // -------------------------------------------------------------------------
    
    // OPTIMIZED
    public List<BookingResponse> getMyBookings(Long customerId) {
        List<BookingResponse> bookings = bookingRepository.findRecentByUserIdWithSeats(customerId, RECENT_BOOKINGS_LIMIT).stream()
            .map(summary -> bookingResponseBuilder(new BookingResponseData(
                summary.booking(),
                summary.movieName(),
                summary.showStartTime(),
                summary.durationMinutes(),
                summary.screenName(),
                summary.theatreName(),
                summary.theatreLocation(),
                summary.seats().stream()
                    .map(this::toSeatResponse)
                    .collect(Collectors.toList()))))
                .collect(Collectors.toList());
            LOGGER.info("event=booking.listed requestId=" + util.RequestLogContext.requestId()
                + " userId=" + customerId + " count=" + bookings.size());
            return bookings;
    }

    // OPTIMIZED 6 -> 3 QUERIES
    public BookingResponse getBooking(Long bookingId, Long customerId) {
        Booking booking = getBookingEntity(bookingId);
        if (!booking.getUserId().equals(customerId)) {
            throw new ForbiddenException("You are not allowed to access this booking");
        }
        ShowRepository.ShowWithDetails details = showRepository.findWithDetails(booking.getShowId())
                .orElseThrow(() -> new NotFoundException("Show not found"));
        Show show = details.show();
        Movie movie = details.movie();
        Screen screen = details.screen();
        Theatre theatre = details.theatre();
        BookingResponse response = bookingResponseBuilder(new BookingResponseData(
            booking,
            movie.getMovieName(),
            show.getShowTiming(),
            movie.getDurationInMinutes(),
            screen.getScreenName(),
            theatre.getTheatreName(),
            theatre.getTheatreLocation(),
            showSeatRepository.findByBookingId(bookingId).stream()
                .map(this::toSeatResponse)
                .collect(Collectors.toList())));
            LOGGER.info("event=booking.viewed requestId=" + util.RequestLogContext.requestId()
                + " bookingId=" + bookingId + " userId=" + customerId);
            return response;
    }

    public void cancelBookingWithOtp(Long bookingId, Long customerId,
                                     String challengeToken, String code) throws SQLException {
        List<ShowSeat> cancelledSeats = Database.inTransaction(() -> {
            if (!otpService.verifyCodeInTransaction(challengeToken, customerId, bookingId,
                    enums.OtpPurpose.CANCELLATION, code)) {
                return null;
            }
            return cancelInternal(bookingId, customerId);
        });
        if (cancelledSeats == null) {
            throw new ValidationException("OTP is invalid");
        }
        removeFromCache(cancelledSeats);
        LOGGER.info("event=booking.cancelled requestId=" + util.RequestLogContext.requestId()
            + " bookingId=" + bookingId + " userId=" + customerId + " method=otp");
    }

    /**
     * Builds the current seat grid from the screen layout and occupied-seat
     * cache. The cache stores only seats that are held or booked.
     */
    // -------------------------------------------------------------------------
    // Seat availability and expiry
    // -------------------------------------------------------------------------

    // OPTIMIZED
    public SeatMapResponse getAvailableSeats(Long showId) {
        Show show = getShow(showId);
        Screen screen = getScreen(show.getScreenId());

        // Use the cached sparse occupancy map whenever it is still valid.
        Map<String, String> occupiedMap = seatAvailabilityCache.get(showId);

        if (occupiedMap == null) {
            // Cache miss: load only occupied seats from the database.
            Map<String, String> seatStatuses = showSeatRepository.findStatusByShowId(showId);
            occupiedMap = new HashMap<>();
            for (Map.Entry<String, String> entry : seatStatuses.entrySet()) {
                String status = ("PENDING".equals(entry.getValue())
                    || "AWAITING_OTP".equals(entry.getValue())) ? "HELD" : "BOOKED";
                occupiedMap.put(entry.getKey(), status);
            }
            occupiedMap = seatAvailabilityCache.put(showId, occupiedMap);
        }

        // Return only static layout and occupied seats. The browser generates
        // available seat buttons from the layout and applies these statuses.
        SeatMapResponse response = new SeatMapResponse(
                screen.getScreenName(),
                screen.getRowRange(),
                screen.getSeatsPerRow(),
                occupiedMap);
            LOGGER.info("event=booking.seats.loaded requestId=" + util.RequestLogContext.requestId()
                + " showId=" + showId + " occupiedCount=" + occupiedMap.size());
            return response;
    }

    /**
     * Releases all expired holds in one transaction and updates the seat cache
     * only after the database changes commit successfully.
     */

    // OPTIMIZED
    public void expirePendingBookings() throws SQLException {
        List<ShowSeat> expiredSeats = Database.inTransaction(() -> {
            LocalDateTime now = LocalDateTime.now();
            List<ShowSeat> releasedSeats = showSeatRepository.findByExpiredBooking(now);
            showSeatRepository.deleteByExpiredBooking(now);
            otpChallengeRepository.deleteByExpiredBooking(now);
            bookingRepository.expirePendingBookings(now);
            return releasedSeats;
        });
        removeFromCache(expiredSeats);
        LOGGER.info("event=booking.expiry.released requestId=" + util.RequestLogContext.requestId()
            + " seatCount=" + expiredSeats.size());
    }

    // -------------------------------------------------------------------------
    // Internal booking workflow steps
    // -------------------------------------------------------------------------

    /** Performs the database portion of creating a temporary OTP hold. */
    private BookingResponse holdTicketsInternal(Long customerId, BookingRequest request,
                                                List<SeatRequest> seats) {
        User customer = getUser(customerId);
        if (customer.getRole() != Role.CUSTOMER) {
            // QUESTION: Do we need to have a log statement here??
            throw new ForbiddenException("Only customers can create bookings");
        }
        // QUESTION: Are all these DB fetches a single read each?? Can we join them all to retrieve them in one query??
        ShowRepository.ShowWithDetails details = showRepository.findWithDetails(request.getShowId())
                .orElseThrow(() -> new NotFoundException("Show not found"));
        Show show = details.show();
        if (!show.getShowTiming().isAfter(LocalDateTime.now())) {
            // QUESTION: Do we need to have a log statement here??
            throw new ValidationException("Cannot book a show that has already started");
        }
        Screen screen = details.screen();
        Movie movie = details.movie();
        Theatre theatre = details.theatre();
        validateSeats(seats, screen);

        int totalAmount;
        try {
            totalAmount = Math.multiplyExact(movie.getTicketPrice(), seats.size());
        } catch (ArithmeticException exception) {
            throw new ValidationException("Booking total is too large");
        }

        LocalDateTime now = LocalDateTime.now();
        Booking booking = new Booking();
        booking.setUserId(customerId);
        booking.setShowId(show.getShowId());
        booking.setStatus(BookingStatus.AWAITING_OTP);
        booking.setTotalAmount(totalAmount);
        booking.setBookingTime(now);
        booking.setExpiresAt(now.plusMinutes(PENDING_MINUTES));
        booking = bookingRepository.save(booking);

        // A successful claim makes the seat unavailable to other bookings.
        for (SeatRequest seat : seats) {
            ShowSeat showSeat = new ShowSeat();
            showSeat.setShowId(show.getShowId());
            showSeat.setRowLabel(seat.getRowLabel());
            showSeat.setSeatNumber(seat.getSeatNumber());
            showSeat.setBookingId(booking.getBookingId());
            if (!showSeatRepository.claimSeat(showSeat)) {
                throw new ConflictException("Seat " + seat.getRowLabel() + seat.getSeatNumber()
                        + " is no longer available");
            }
        }
        return bookingResponseBuilder(new BookingResponseData(
            booking,
            movie.getMovieName(),
            show.getShowTiming(),
            movie.getDurationInMinutes(),
            screen.getScreenName(),
            theatre.getTheatreName(),
            theatre.getTheatreLocation(),
            seats.stream().map(this::toSeatResponse).collect(Collectors.toList())));
    }

    /** Loads, validates, charges, and confirms an OTP-protected booking hold. */
    private BookingResult confirmHeldBookingInternal(Long bookingId, Long customerId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
            .orElseThrow(() -> new NotFoundException("Booking not found"));
        if (!booking.getUserId().equals(customerId)) {
            throw new ForbiddenException("You are not allowed to confirm this booking");
        }
        if (booking.getStatus() != BookingStatus.AWAITING_OTP) {
            throw new ValidationException("Booking is not awaiting verification");
        }
        if (booking.getExpiresAt() == null || LocalDateTime.now().isAfter(booking.getExpiresAt())) {
            throw new ValidationException("Booking hold has expired");
        }

        // OPTIMIZED READS 
        User customer = getUser(customerId);
        ShowRepository.ShowWithDetails details = showRepository.findWithDetails(booking.getShowId())
                .orElseThrow(() -> new NotFoundException("Show not found"));
        Show show = details.show();
        Movie movie = details.movie();
        Screen screen = details.screen();
        Theatre theatre = details.theatre();
        List<ShowSeat> seats = showSeatRepository.findByBookingId(bookingId);
        try {
            paymentService.processPayment(customerId, theatre.getAdminId(), booking.getTotalAmount());
        } catch (RuntimeException error) {
                LOGGER.log(Level.WARNING, "event=payment.failed requestId=" + util.RequestLogContext.requestId()
                    + " bookingId=" + bookingId + " userId=" + customerId
                    + " reason=payment_rejected", error);
            throw error;
        }

        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setExpiresAt(null);
        bookingRepository.update(booking);
        
        // BOOKING CONFIRMATION ENDS HERE. GATE TOKEN SERVICE, EMAIL SERVICE STARTS
        LocalDateTime showEnd = show.getShowTiming().plusMinutes(movie.getDurationInMinutes());
        String gateUrl = gateTokenService.issue(bookingId, showEnd);
        ConfirmationEmail email = new ConfirmationEmail(customer.getEmail(), customer.getName(), bookingId,
                movie.getMovieName(), theatre.getTheatreName(), theatre.getTheatreLocation(), show.getShowTiming(),
                seats.stream().map(seat -> seat.getRowLabel() + "-" + seat.getSeatNumber()).toList(),
                booking.getTotalAmount(), gateUrl);
        LOGGER.info("event=booking.confirmed requestId=" + util.RequestLogContext.requestId()
            + " bookingId=" + bookingId + " userId=" + customerId + " method=otp");
        return new BookingResult(bookingResponseBuilder(new BookingResponseData(
            booking,
            movie.getMovieName(),
            show.getShowTiming(),
            movie.getDurationInMinutes(),
            screen.getScreenName(),
            theatre.getTheatreName(),
            theatre.getTheatreLocation(),
            seats.stream().map(this::toSeatResponse).collect(Collectors.toList()))), email);
    }

    /** Cancels a confirmed booking and returns its seats for cache cleanup. */
    private List<ShowSeat> cancelInternal(Long bookingId, Long customerId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
            .orElseThrow(() -> new NotFoundException("Booking not found"));
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

        return cancelledSeats;
    }

    public void validateCancellation(Long bookingId, Long customerId) {
        cancelEligibility(bookingId, customerId);
    }

    private void cancelEligibility(Long bookingId, Long customerId) {
        Booking booking = getBookingEntity(bookingId);
        if (!booking.getUserId().equals(customerId)) {
            throw new ForbiddenException("You are not allowed to cancel this booking");
        }
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new ValidationException("Only confirmed bookings can be cancelled");
        }
        if (ShowTimes.hasStarted(getShow(booking.getShowId()).getShowTiming(), LocalDateTime.now())) {
            throw new ValidationException("Cannot cancel a booking after the show has started");
        }
    }

    // -------------------------------------------------------------------------
    // Validation, mapping, and cache helpers
    // -------------------------------------------------------------------------

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


    /**
     * This helper method validates the seat requests against the screen layout and checks for duplicates.
     * @param seats
     * @param screen
     */
    private void validateSeats(List<SeatRequest> seats, Screen screen) {
        List<String> validRows = ShowTimes.generateRows(screen.getRowRange());
        Set<String> selected = new HashSet<>();
        for (SeatRequest seat : seats) {
            if (seat.getRowLabel() == null || !validRows.contains(seat.getRowLabel().trim().toUpperCase())) {
                throw new ValidationException("Invalid row: " + seat.getRowLabel());
            }
            seat.setRowLabel(seat.getRowLabel().trim().toUpperCase());
            if (seat.getSeatNumber() <= 0 || seat.getSeatNumber() > screen.getSeatsPerRow()) {
                throw new ValidationException("Invalid seat number: " + seat.getSeatNumber());
            }
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

    private Theatre getTheatre(Long theatreId) {
        return theatreRepository.findById(theatreId)
                .orElseThrow(() -> new NotFoundException("Theatre not found"));
    }

    private Booking getBookingEntity(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking not found"));
    }


    private BookingResponse bookingResponseBuilder(BookingResponseData data) {
        Booking booking = data.booking();
        BookingResponse response = new BookingResponse();
        response.setBookingId(booking.getBookingId());
        response.setShowId(booking.getShowId());
        response.setStatus(booking.getStatus());
        response.setMovieName(data.movieName());
        response.setShowStartTime(data.showStartTime());
        response.setDurationMinutes(data.durationMinutes());
        response.setScreenName(data.screenName());
        response.setTheatreName(data.theatreName());
        response.setTheatreLocation(data.theatreLocation());
        response.setTotalAmount(booking.getTotalAmount());
        response.setBookingTime(booking.getBookingTime());
        response.setSeats(data.seats());
        return response;
    }

    private void updateCache(BookingResponse booking, String status) {
        for (SeatResponse seat : booking.getSeats()) {
            seatAvailabilityCache.updateSeat(booking.getShowId(),
                    seat.getRowLabel() + "-" + seat.getSeatNumber(), status);
        }
    }

    // MERGE INTO SeatAvailabilityCache.java
    private void removeFromCache(List<ShowSeat> seats) {
        for (ShowSeat seat : seats) {
            seatAvailabilityCache.removeSeat(seat.getShowId(),
                    seat.getRowLabel() + "-" + seat.getSeatNumber());
        }
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

    private record BookingResponseData(
            Booking booking,
            String movieName,
            LocalDateTime showStartTime,
            int durationMinutes,
            String screenName,
            String theatreName,
            String theatreLocation,
            List<SeatResponse> seats) {
    }

    private record BookingResult(BookingResponse response, ConfirmationEmail confirmationEmail) {
    }
}
