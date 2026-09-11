import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import org.json.JSONObject;
import org.json.JSONArray;

/**
 * Concrete simulation worker for one customer.
 *
 * This class owns one customer's configuration and will later own that
 * customer's independent HTTP session, authentication state, and workflow data.
 */
public class CustomerSimulation extends Simulation {

    private static final class SeatConflictException
            extends IllegalStateException {

        private SeatConflictException(String message) {
            super(message);
        }
    }

    private static final long SEAT_RETRY_TIMEOUT_MILLIS = 60_000L;
    private static final int MIN_WAIT_MILLIS = 250;
    private static final int MAX_WAIT_MILLIS = 1_500;
    
    private final String baseUrl;
    private final String movieTitle;
    private final Long showId;
    private final int ticketCost;
    private final TestCustomer customer;
    private final SimulationLogger logger;
    private final CookieManager cookieManager =
        new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient httpClient = HttpClient.newBuilder()
        .cookieHandler(cookieManager)
        .build();
    private String csrfToken;
    private Long selectedMovieId;
    private LocalDateTime selectedShowTime;
    private int walletBalance;
    private int bookingAmount;

    public CustomerSimulation(
            String baseUrl,
            String movieTitle,
            Long showId,
            int initialWalletBalance,
            int ticketCost,
            TestCustomer customer,
            SimulationLogger logger) {
        this.baseUrl = baseUrl;
        this.movieTitle = movieTitle;
        this.showId = showId;
        this.walletBalance = initialWalletBalance;
        this.ticketCost = ticketCost;
        this.customer = customer;
        this.logger = logger;
    }

    public CustomerResult runWorkflow() {
        boolean loggedIn = false;

        try {
            login(new LoginInput(customer.email(), customer.password()));
            loggedIn = true;
            findMovie();
            findShow();

            long retryDeadline = System.currentTimeMillis()
                    + SEAT_RETRY_TIMEOUT_MILLIS;

            while (System.currentTimeMillis() < retryDeadline) {
                List<SeatResult> seatMap = getAvailableSeats();
                boolean hasAvailable = hasAvailableSeats(seatMap);
                boolean hasHeld = hasHeldSeats(seatMap);

                if (!hasAvailable) {
                    if (hasHeld) {
                        waitRandomInterval();
                        continue;
                    }

                    logout();
                    loggedIn = false;
                    return new CustomerResult(
                            customer.email(),
                            false,
                            "No available seats remain"
                    );
                }

                List<SeatResult> availableSeats = seatMap.stream()
                        .filter(SeatResult::available)
                        .toList();
                SeatSelectionResult selection = chooseSeats(availableSeats);

                try {
                    BookingOtpResult bookingOtp = requestBookingOtp(
                            new BookingInput(selection.seats())
                    );
                    String otp = requireSimulationOtp(
                            bookingOtp.simulationOtp(),
                            "booking"
                    );
                    BookingResult booking = verifyBookingOtp(
                            new BookingOtpInput(
                                    bookingOtp.bookingId(),
                                    bookingOtp.challengeToken(),
                                    otp
                            )
                    );

                    waitRandomInterval();

                    if (shouldCancel()) {
                        CancellationOtpResult cancellationOtp =
                                requestCancellationOtp(booking.bookingId());
                        String cancellationCode = requireSimulationOtp(
                                cancellationOtp.simulationOtp(),
                                "cancellation"
                        );
                        CancellationResult cancellation = verifyCancellationOtp(
                            new CancellationOtpInput(
                                cancellationOtp.bookingId(),
                                cancellationOtp.challengeToken(),
                                cancellationCode
                            )
                        );

                        logger.log(
                            java.time.Instant.now().toString(),
                            Thread.currentThread().getName(),
                            "CANCELLATION_COMPLETE",
                            "bookingId=" + cancellation.bookingId()
                                + " refund=" + cancellation.refundAmount()
                        );
                    }

                    logout();
                    loggedIn = false;
                    return new CustomerResult(
                            customer.email(),
                            true,
                            "Workflow completed for booking " + booking.bookingId()
                    );
                } catch (IllegalStateException exception) {
                    if (!isSeatConflict(exception)) {
                        throw exception;
                    }
                    logger.log(
                            java.time.Instant.now().toString(),
                            Thread.currentThread().getName(),
                            "BOOKING_RETRY",
                            "Seat conflict; refreshing seat map"
                    );
                    waitRandomInterval();
                }
            }

            logout();
            loggedIn = false;
            return new CustomerResult(
                    customer.email(),
                    false,
                    "Seat retry timeout exceeded"
            );
        } catch (Exception exception) {
            logger.log(
                    java.time.Instant.now().toString(),
                    Thread.currentThread().getName(),
                    "WORKFLOW",
                    "FAILED: " + exception.getMessage()
            );
            if (loggedIn) {
                try {
                    logout();
                } catch (Exception logoutException) {
                    logger.log(
                            java.time.Instant.now().toString(),
                            Thread.currentThread().getName(),
                            "LOGOUT",
                            "FAILED during cleanup: "
                                    + logoutException.getMessage()
                    );
                }
            }
            return new CustomerResult(
                    customer.email(),
                    false,
                    exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage()
            );
        }
    }

    private void waitRandomInterval() {
        int waitMillis = ThreadLocalRandom.current().nextInt(
                MIN_WAIT_MILLIS,
                MAX_WAIT_MILLIS + 1
        );
        try {
            Thread.sleep(waitMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Workflow wait interrupted", exception
            );
        }
    }

    private boolean shouldCancel() {
        return ThreadLocalRandom.current().nextBoolean();
    }

    private String requireSimulationOtp(String otp, String purpose) {
        if (otp == null || otp.isBlank()) {
            throw new IllegalStateException(
                    "No simulation OTP returned for " + purpose
            );
        }
        return otp;
    }

    private boolean isSeatConflict(IllegalStateException exception) {
        return exception instanceof SeatConflictException;
    }

    /** Registers this customer through POST /api/auth/register. */
    public RegisterResult register(RegisterInput input) throws IOException, InterruptedException {
        // Send POST /api/auth/register with name, email, and password.
        String endpoint = baseUrl + "/auth/register";
        String requestBody = """
                {
                    "name": "%s",
                    "email": "%s",
                    "password": "%s"
                }
                """.formatted(
                        input.name(), 
                        input.email(), 
                        input.password()
                    );
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        
        HttpResponse<String> response = 
                httpClient.send(
                    request, 
                    HttpResponse.BodyHandlers.ofString()
                );
        
        // Confirm the response is successful and return the created user.
        if(response.statusCode() != 201) {
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "Register",
                "Failed with status code: " + response.statusCode()
            );

            throw new IllegalStateException(
                "Failed to register user: " + response.body()
            );
        }

        logger.log(
            java.time.Instant.now().toString(),
            Thread.currentThread().getName(),
            "Register",
            "Successful registration"
        );

        return new RegisterResult(
            new JSONObject(response.body()).getLong("userId"),
            input.email()
        );
    }

    @Override
    /** Logs in through POST /api/auth/login and stores the session CSRF token. */
    protected LoginResult login(LoginInput input) {
        String endpoint = baseUrl + "/auth/login";
        String requestBody = new JSONObject()
            .put("email", input.email())
            .put("password", input.password())
            .toString();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "LOGIN",
                "FAILED HTTP " + response.statusCode()
            );
            throw new IllegalStateException(
                "Login failed: " + response.body()
            );
            }

            JSONObject responseJson = new JSONObject(response.body());
            Long userId = responseJson.getLong("userId");
            csrfToken = responseJson.getString("csrfToken");
            walletBalance = responseJson.getInt("walletBalance");

            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "LOGIN",
                "SUCCESS userId=" + userId
            );

            return new LoginResult(userId, csrfToken);
        } catch (IOException exception) {
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "LOGIN",
                "FAILED I/O: " + exception.getMessage()
            );
            throw new IllegalStateException("Login request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "LOGIN",
                "INTERRUPTED"
            );
            throw new IllegalStateException("Login request interrupted", exception);
        }
    }

    @Override
    /** Requests GET /api/movies and returns the configured movie match. */
    protected MovieResult findMovie() {
        String endpoint = baseUrl + "/movies";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .GET()
            .build();

        try {
            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "FIND_MOVIE",
                "FAILED HTTP " + response.statusCode()
            );
            throw new IllegalStateException(
                "Movie lookup failed: " + response.body()
            );
            }

            JSONArray movies = new JSONArray(response.body());
            String requestedTitle = movieTitle.trim().toLowerCase(Locale.ROOT);

            for (int index = 0; index < movies.length(); index++) {
                JSONObject movie = movies.getJSONObject(index);
                String movieName = movie.getString("movieName");

                if (movieName.trim().toLowerCase(Locale.ROOT).equals(requestedTitle)) {
                    Long movieId = movie.getLong("movieId");

                    logger.log(
                        java.time.Instant.now().toString(),
                        Thread.currentThread().getName(),
                        "FIND_MOVIE",
                        "SUCCESS movieId=" + movieId + " title=" + movieName
                    );

                    selectedMovieId = movieId;
                    return new MovieResult(movieId, movieName);
                }
            }

            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "FIND_MOVIE",
                "FAILED movie not found: " + movieTitle
            );
            throw new IllegalStateException(
                "Movie not found: " + movieTitle
            );
        } catch (IOException exception) {
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "FIND_MOVIE",
                "FAILED I/O: " + exception.getMessage()
            );
            throw new IllegalStateException("Movie lookup request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "FIND_MOVIE",
                "INTERRUPTED"
            );
            throw new IllegalStateException("Movie lookup request interrupted", exception);
        }
    }

    @Override
    /** Requests shows for the selected movie and returns the configured show. */
    protected ShowResult findShow() {
        if (selectedMovieId == null) {
            throw new IllegalStateException(
                "Cannot find show before a movie has been selected"
            );
        }

        String endpoint = baseUrl + "/shows?movieId=" + selectedMovieId;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .GET()
            .build();

        try {
            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "FIND_SHOW",
                "FAILED HTTP " + response.statusCode()
            );
            throw new IllegalStateException(
                "Show lookup failed: " + response.body()
            );
            }

            JSONArray shows = new JSONArray(response.body());
            for (int index = 0; index < shows.length(); index++) {
            JSONObject show = shows.getJSONObject(index);
            Long currentShowId = show.getLong("showId");

            if (currentShowId.equals(showId)) {
                String showTiming = show.getString("showTiming");
                selectedShowTime = LocalDateTime.parse(showTiming);

                logger.log(
                    java.time.Instant.now().toString(),
                    Thread.currentThread().getName(),
                    "FIND_SHOW",
                    "SUCCESS showId=" + currentShowId
                );

                return new ShowResult(currentShowId, selectedMovieId, showTiming);
            }
            }

            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "FIND_SHOW",
                "FAILED show not found: " + showId
            );
            throw new IllegalStateException("Show not found: " + showId);
        } catch (IOException exception) {
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "FIND_SHOW",
                "FAILED I/O: " + exception.getMessage()
            );
            throw new IllegalStateException("Show lookup request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "FIND_SHOW",
                "INTERRUPTED"
            );
            throw new IllegalStateException("Show lookup request interrupted", exception);
        }
    }

    @Override
    /** Requests the complete seat map from GET /api/shows/{showId}/seats. */
    protected List<SeatResult> getAvailableSeats() {
        String endpoint = baseUrl + "/shows/" + showId + "/seats";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                logger.log(
                        java.time.Instant.now().toString(),
                        Thread.currentThread().getName(),
                        "GET_AVAILABLE_SEATS",
                        "FAILED HTTP " + response.statusCode()
                );
                throw new IllegalStateException(
                        "Seat lookup failed: " + response.body()
                );
            }

            JSONArray seats = new JSONArray(response.body());
            List<SeatResult> seatMap = new ArrayList<>();

            for (int index = 0; index < seats.length(); index++) {
                JSONObject seat = seats.getJSONObject(index);
                seatMap.add(new SeatResult(
                        seat.getString("rowLabel"),
                        seat.getInt("seatNumber"),
                        seat.getString("status"),
                        seat.getBoolean("available")
                ));
            }

            logger.log(
                    java.time.Instant.now().toString(),
                    Thread.currentThread().getName(),
                    "GET_AVAILABLE_SEATS",
                    "SUCCESS showId=" + showId
                            + " available=" + seatMap.stream()
                            .filter(SeatResult::available)
                            .count()
                            + " held=" + seatMap.stream()
                            .filter(seat -> "HELD".equalsIgnoreCase(seat.status()))
                            .count()
            );
            return seatMap;
        } catch (IOException exception) {
            logger.log(
                    java.time.Instant.now().toString(),
                    Thread.currentThread().getName(),
                    "GET_AVAILABLE_SEATS",
                    "FAILED I/O: " + exception.getMessage()
            );
            throw new IllegalStateException("Seat lookup request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(
                    java.time.Instant.now().toString(),
                    Thread.currentThread().getName(),
                    "GET_AVAILABLE_SEATS",
                    "INTERRUPTED"
            );
            throw new IllegalStateException("Seat lookup request interrupted", exception);
        }
    }

    private boolean hasAvailableSeats(List<SeatResult> seats) {
        return seats.stream().anyMatch(SeatResult::available);
    }

    private boolean hasHeldSeats(List<SeatResult> seats) {
        return seats.stream().anyMatch(seat -> "HELD".equalsIgnoreCase(seat.status()));
    }

    @Override
    /** Randomly selects one to four seats from the available seats. */
    protected SeatSelectionResult chooseSeats(List<SeatResult> availableSeats) {
        if (availableSeats.isEmpty()) {
            throw new IllegalStateException("Cannot choose from an empty seat list");
        }

        List<SeatResult> shuffledSeats = new ArrayList<>(availableSeats);
        Collections.shuffle(shuffledSeats, ThreadLocalRandom.current());

        int maximumSeats = Math.min(4, shuffledSeats.size());
        int selectedCount = ThreadLocalRandom.current().nextInt(1, maximumSeats + 1);
        List<SeatResult> selectedSeats = List.copyOf(
                shuffledSeats.subList(0, selectedCount)
        );

        logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "CHOOSE_SEATS",
                "SUCCESS showId=" + showId + " selected=" + selectedSeats.size()
        );
        return new SeatSelectionResult(showId, selectedSeats);
    }

    @Override
    /** Holds selected seats and requests a booking OTP. */
    protected BookingOtpResult requestBookingOtp(BookingInput input) {
        if (input == null || input.seats() == null || input.seats().isEmpty()) {
            throw new IllegalArgumentException("At least one seat is required");
        }

        String endpoint = baseUrl + "/bookings/otp/request";
        bookingAmount = ticketCost * input.seats().size();
        String requestBody = new JSONObject()
                .put("showId", showId)
                .put("seats", seatRequestArray(input.seats()))
                .toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("X-CSRF-Token", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() == 409) {
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "REQUEST_BOOKING_OTP",
                "SEAT_CONFLICT"
            );
            throw new SeatConflictException(
                "Selected seats are no longer available"
            );
            }

            if (response.statusCode() != 200) {
                logger.log(
                        java.time.Instant.now().toString(),
                        Thread.currentThread().getName(),
                        "REQUEST_BOOKING_OTP",
                        "FAILED HTTP " + response.statusCode()
                );
                throw new IllegalStateException(
                        "Booking OTP request failed: " + response.body()
                );
            }

            JSONObject responseJson = new JSONObject(response.body());
            JSONObject booking = responseJson.getJSONObject("booking");
            BookingOtpResult result = new BookingOtpResult(
                    booking.getLong("bookingId"),
                    responseJson.getString("challengeToken"),
                    responseJson.get("expiresAt").toString(),
                    responseJson.optString("simulationOtp", null)
            );

                logger.log(
                    java.time.Instant.now().toString(),
                    Thread.currentThread().getName(),
                    "WALLET_BEFORE_BOOKING",
                    "balance=" + walletBalance + " ticketCost=" + ticketCost
                        + " seats=" + input.seats().size()
                        + " amount=" + bookingAmount
                );

            logger.log(
                    java.time.Instant.now().toString(),
                    Thread.currentThread().getName(),
                    "REQUEST_BOOKING_OTP",
                    "SUCCESS bookingId=" + result.bookingId()
            );
            return result;
        } catch (IOException exception) {
            logger.log(
                    java.time.Instant.now().toString(),
                    Thread.currentThread().getName(),
                    "REQUEST_BOOKING_OTP",
                    "FAILED I/O: " + exception.getMessage()
            );
            throw new IllegalStateException("Booking OTP request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(
                    java.time.Instant.now().toString(),
                    Thread.currentThread().getName(),
                    "REQUEST_BOOKING_OTP",
                    "INTERRUPTED"
            );
            throw new IllegalStateException(
                    "Booking OTP request interrupted", exception
            );
        }
    }

    private JSONArray seatRequestArray(List<SeatResult> seats) {
        JSONArray seatArray = new JSONArray();
        for (SeatResult seat : seats) {
            seatArray.put(new JSONObject()
                    .put("rowLabel", seat.rowLabel())
                    .put("seatNumber", seat.seatNumber()));
        }
        return seatArray;
    }

    @Override
    /** Verifies the booking OTP and confirms payment. */
    protected BookingResult verifyBookingOtp(BookingOtpInput input) {
        if (input == null || input.challengeToken() == null
                || input.code() == null) {
            throw new IllegalArgumentException(
                    "Booking challenge token and code are required"
            );
        }

        String endpoint = baseUrl + "/bookings/"
                + input.bookingId() + "/otp/verify";
        String requestBody = new JSONObject()
                .put("challengeToken", input.challengeToken())
                .put("code", input.code())
                .toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("X-CSRF-Token", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                logger.log(
                        java.time.Instant.now().toString(),
                        Thread.currentThread().getName(),
                        "VERIFY_BOOKING_OTP",
                        "FAILED HTTP " + response.statusCode()
                );
                throw new IllegalStateException(
                        "Booking OTP verification failed: " + response.body()
                );
            }

            JSONObject booking = new JSONObject(response.body());
            BookingResult result = new BookingResult(
                    booking.getLong("bookingId"),
                    booking.getString("status"),
                    booking.getInt("totalAmount")
            );

                bookingAmount = result.totalAmount();
                int balanceBeforeBooking = walletBalance;
                walletBalance -= bookingAmount;
                logger.log(
                    java.time.Instant.now().toString(),
                    Thread.currentThread().getName(),
                    "WALLET_AFTER_BOOKING",
                    "before=" + balanceBeforeBooking + " debit=" + bookingAmount
                        + " after=" + walletBalance
                );

            logger.log(
                    java.time.Instant.now().toString(),
                    Thread.currentThread().getName(),
                    "VERIFY_BOOKING_OTP",
                    "SUCCESS bookingId=" + result.bookingId()
                            + " status=" + result.status()
            );
            return result;
        } catch (IOException exception) {
            logger.log(
                    java.time.Instant.now().toString(),
                    Thread.currentThread().getName(),
                    "VERIFY_BOOKING_OTP",
                    "FAILED I/O: " + exception.getMessage()
            );
            throw new IllegalStateException(
                    "Booking OTP verification failed", exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(
                    java.time.Instant.now().toString(),
                    Thread.currentThread().getName(),
                    "VERIFY_BOOKING_OTP",
                    "INTERRUPTED"
            );
            throw new IllegalStateException(
                    "Booking OTP verification interrupted", exception
            );
        }
    }

    @Override
    /** Requests the cancellation OTP for a confirmed booking. */
    protected CancellationOtpResult requestCancellationOtp(Long bookingId) {
        if (bookingId == null) {
            throw new IllegalArgumentException("Booking ID is required");
        }

        String endpoint = baseUrl + "/bookings/" + bookingId
            + "/cancel/otp/request";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("X-CSRF-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        try {
            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "REQUEST_CANCELLATION_OTP",
                "FAILED HTTP " + response.statusCode()
            );
            throw new IllegalStateException(
                "Cancellation OTP request failed: " + response.body()
            );
            }

            JSONObject responseJson = new JSONObject(response.body());
            int refundAmount = calculateRefundAmount();
            CancellationOtpResult result = new CancellationOtpResult(
                bookingId,
                responseJson.getString("challengeToken"),
                responseJson.get("expiresAt").toString(),
                responseJson.optString("simulationOtp", null)
            );

                logger.log(
                    java.time.Instant.now().toString(),
                    Thread.currentThread().getName(),
                    "WALLET_BEFORE_CANCELLATION",
                    "balance=" + walletBalance + " expectedRefund=" + refundAmount
                );

            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "REQUEST_CANCELLATION_OTP",
                "SUCCESS bookingId=" + bookingId
            );
            return result;
        } catch (IOException exception) {
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "REQUEST_CANCELLATION_OTP",
                "FAILED I/O: " + exception.getMessage()
            );
            throw new IllegalStateException(
                "Cancellation OTP request failed", exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "REQUEST_CANCELLATION_OTP",
                "INTERRUPTED"
            );
            throw new IllegalStateException(
                "Cancellation OTP request interrupted", exception
            );
        }
    }

    @Override
    /** Verifies the cancellation OTP and completes the refund workflow. */
    protected CancellationResult verifyCancellationOtp(CancellationOtpInput input) {
        if (input == null || input.bookingId() == null
            || input.challengeToken() == null || input.code() == null) {
            throw new IllegalArgumentException(
                "Cancellation booking ID, challenge token, and code are required"
            );
        }

        String endpoint = baseUrl + "/bookings/"
            + input.bookingId() + "/cancel/otp/verify";
        String requestBody = new JSONObject()
            .put("challengeToken", input.challengeToken())
            .put("code", input.code())
            .toString();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("X-CSRF-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 204) {
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "VERIFY_CANCELLATION_OTP",
                "FAILED HTTP " + response.statusCode()
            );
            throw new IllegalStateException(
                "Cancellation OTP verification failed: " + response.body()
            );
            }

            CancellationResult result = new CancellationResult(
                input.bookingId(),
                "CANCELLED",
                    calculateRefundAmount()
            );
                int balanceBeforeCancellation = walletBalance;
                walletBalance += result.refundAmount();
                logger.log(
                    java.time.Instant.now().toString(),
                    Thread.currentThread().getName(),
                    "WALLET_AFTER_CANCELLATION",
                    "before=" + balanceBeforeCancellation + " credit="
                        + result.refundAmount() + " after=" + walletBalance
                );
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "VERIFY_CANCELLATION_OTP",
                "SUCCESS bookingId=" + input.bookingId()
            );
            return result;
        } catch (IOException exception) {
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "VERIFY_CANCELLATION_OTP",
                "FAILED I/O: " + exception.getMessage()
            );
            throw new IllegalStateException(
                "Cancellation OTP verification failed", exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "VERIFY_CANCELLATION_OTP",
                "INTERRUPTED"
            );
            throw new IllegalStateException(
                "Cancellation OTP verification interrupted", exception
            );
        }
    }

    @Override
    /** Logs out through POST /api/auth/logout and clears local session data. */
    protected void logout() {
        String endpoint = baseUrl + "/auth/logout";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("X-CSRF-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        try {
            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 204) {
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "LOGOUT",
                "FAILED HTTP " + response.statusCode()
            );
            throw new IllegalStateException(
                "Logout failed: " + response.body()
            );
            }

            cookieManager.getCookieStore().removeAll();
            csrfToken = null;
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "LOGOUT",
                "SUCCESS"
            );
        } catch (IOException exception) {
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "LOGOUT",
                "FAILED I/O: " + exception.getMessage()
            );
            throw new IllegalStateException("Logout request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(
                java.time.Instant.now().toString(),
                Thread.currentThread().getName(),
                "LOGOUT",
                "INTERRUPTED"
            );
            throw new IllegalStateException("Logout request interrupted", exception);
        }
    }

    private int calculateRefundAmount() {
        if (selectedShowTime == null || bookingAmount == 0) {
            return bookingAmount;
        }
        if (LocalDateTime.now().isBefore(selectedShowTime.minusMinutes(30))) {
            return bookingAmount;
        }
        return bookingAmount - bookingAmount / 4;
    }
}
