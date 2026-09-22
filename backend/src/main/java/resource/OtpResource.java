package resource;

import dto.response.BookingOtpResponse;
import dto.request.OtpVerifyRequest;
import dto.request.BookingRequest;
import dto.response.BookingResponse;
import dto.response.OtpChallengeResponse;
import enums.OtpPurpose;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import model.User;
import service.AuthService;
import service.BookingService;
import service.OtpEmailService;
import service.OtpService;
import config.EnvironmentConfig;
import util.RequestUsers;
import util.RequestLogContext;

import java.util.logging.Logger;

@Path("/bookings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OtpResource {

    private static final Logger LOGGER = Logger.getLogger(OtpResource.class.getName());

    private final AuthService authService;
    private final BookingService bookingService;
    private final OtpService otpService;
    private final OtpEmailService otpEmailService;

    @Inject
    public OtpResource(AuthService authService, BookingService bookingService,
                       OtpService otpService, OtpEmailService otpEmailService) {
        this.authService = authService;
        this.bookingService = bookingService;
        this.otpService = otpService;
        this.otpEmailService = otpEmailService;
    }

    @POST
    @Path("/otp/request")
    public BookingOtpResponse requestBookingOtp(BookingRequest body,
                                                @Context HttpServletRequest request) throws java.sql.SQLException {
        Long userId = RequestUsers.requireCustomer(request);
        User user = authService.getById(userId);
        BookingResponse booking = bookingService.holdTickets(userId, body);
        try {
            OtpService.Challenge challenge = otpService.create(userId, booking.getBookingId(), OtpPurpose.BOOKING);
            deliverOtp(user.getEmail(), challenge.code(), "booking confirmation");
            return new BookingOtpResponse(booking, challenge.token(), mask(user.getEmail()),
                    challenge.expiresAt(), challenge.resendAvailableAt(), simulationOtp(challenge));
        } catch (RuntimeException error) {
                LOGGER.warning("event=booking.otp.failed requestId=" + RequestLogContext.requestId()
                    + " userId=" + userId + " bookingId=" + booking.getBookingId()
                    + " reason=email_delivery_failed");
            bookingService.expireBookingHold(booking.getBookingId(), userId);
            throw error;
        }
    }

    @POST
    @Path("/{id}/otp/verify")
    public BookingResponse verifyBookingOtp(@PathParam("id") Long bookingId,
                                            OtpVerifyRequest body, // Why do we need a body here? What's the alternate? 
                                            @Context HttpServletRequest request) throws java.sql.SQLException {
        Long userId = RequestUsers.requireCustomer(request);
        return bookingService.confirmHeldBookingWithOtp(bookingId, userId,
            body == null ? null : body.challengeToken(),
            body == null ? null : body.code());
    }

    @POST
    @Path("/{id}/otp/resend")
    public BookingOtpResponse resendBookingOtp(@PathParam("id") Long bookingId,
                                               @Context HttpServletRequest request) {
        Long userId = RequestUsers.requireCustomer(request);
        User user = authService.getById(userId);
        OtpService.Challenge challenge = otpService.resend(userId, bookingId, OtpPurpose.BOOKING);
        deliverOtp(user.getEmail(), challenge.code(), "booking confirmation");
        return new BookingOtpResponse(bookingService.getBooking(bookingId, userId),
            challenge.token(), mask(user.getEmail()), challenge.expiresAt(),
            challenge.resendAvailableAt(), simulationOtp(challenge));
    }

    @POST
    @Path("/{id}/cancel/otp/request")
    public OtpChallengeResponse requestCancellationOtp(@PathParam("id") Long bookingId,
                                                       @Context HttpServletRequest request) {
        Long userId = RequestUsers.requireCustomer(request);
        User user = authService.getById(userId);
        bookingService.validateCancellation(bookingId, userId);
        OtpService.Challenge challenge = otpService.create(userId, bookingId, OtpPurpose.CANCELLATION);
        deliverOtp(user.getEmail(), challenge.code(), "booking cancellation");
        return new OtpChallengeResponse(challenge.id(), challenge.token(), mask(user.getEmail()),
            challenge.expiresAt(), challenge.resendAvailableAt(), simulationOtp(challenge));
    }

    @POST
    @Path("/{id}/cancel/otp/verify")
    public Response verifyCancellationOtp(@PathParam("id") Long bookingId,
                                          OtpVerifyRequest body,
                                          @Context HttpServletRequest request) throws java.sql.SQLException {
        Long userId = RequestUsers.requireCustomer(request);
        bookingService.cancelBookingWithOtp(bookingId, userId,
            body == null ? null : body.challengeToken(),
            body == null ? null : body.code());
        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/cancel/otp/resend")
    public OtpChallengeResponse resendCancellationOtp(@PathParam("id") Long bookingId,
                                                      @Context HttpServletRequest request) {
        Long userId = RequestUsers.requireCustomer(request);
        User user = authService.getById(userId);
        bookingService.validateCancellation(bookingId, userId);
        OtpService.Challenge challenge = otpService.resend(userId, bookingId, OtpPurpose.CANCELLATION);
        deliverOtp(user.getEmail(), challenge.code(), "booking cancellation");
        return new OtpChallengeResponse(challenge.id(), challenge.token(),
            mask(user.getEmail()), challenge.expiresAt(), challenge.resendAvailableAt(),
            simulationOtp(challenge));
    }

    // HELPER 
    private void deliverOtp(String email, String code, String purpose) {
        if (!simulationMode()) {
            otpEmailService.send(email, code, purpose);
        }
    }

    // HELPER
    private String simulationOtp(OtpService.Challenge challenge) {
        return simulationMode() ? challenge.code() : null;
    }

    // HELPER
    private boolean simulationMode() {
        return "SIMULATION".equalsIgnoreCase(
                EnvironmentConfig.get("OTP_DELIVERY_MODE", "SMTP"));
    }

    // HELPER
    private String mask(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        return at <= 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}