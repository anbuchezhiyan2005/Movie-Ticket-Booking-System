package service;

import config.EnvironmentConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import repository.BookingGateTokenRepository;
import util.HmacUtil;

@Singleton
public class GateValidationService {

    private final BookingGateTokenRepository tokenRepository;
    private final String hmacSecret;

    @Inject
    public GateValidationService(BookingGateTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
        this.hmacSecret = EnvironmentConfig.get("QR_HMAC_SECRET");
    }

    public ValidationResult redeem(String token, String signature, String deviceId) {
        if (token == null || token.isBlank() || !HmacUtil.verify("t=" + token, signature, hmacSecret)) {
            return ValidationResult.rejected(Reason.INVALID_TOKEN, "Ticket QR code is invalid.");
        }

        String tokenHash = HmacUtil.sha256(token);
        if (tokenRepository.consume(tokenHash, deviceId)) {
            return ValidationResult.success();
        }

        return tokenRepository.findReason(tokenHash)
                .map(reason -> ValidationResult.rejected(toReason(reason), toReason(reason).message))
                .orElseGet(() -> ValidationResult.rejected(Reason.INVALID_TOKEN, "Ticket QR code is invalid."));
    }

    private Reason toReason(BookingGateTokenRepository.Rejection rejection) {
        return switch (rejection) {
            case USED -> Reason.USED;
            case BOOKING_CANCELLED -> Reason.BOOKING_CANCELLED;
            case TOKEN_EXPIRED -> Reason.TOKEN_EXPIRED;
            case SHOW_EXPIRED -> Reason.SHOW_EXPIRED;
            case INVALID_TOKEN -> Reason.INVALID_TOKEN;
        };
    }

    public enum Reason {
        ACCESS_GRANTED("Ticket verified. Welcome!"),
        USED("This ticket has already been used."),
        SHOW_EXPIRED("This ticket cannot be used because the show has ended."),
        BOOKING_CANCELLED("This booking has been cancelled."),
        TOKEN_EXPIRED("This ticket has expired."),
        INVALID_TOKEN("Ticket QR code is invalid.");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }

    public record ValidationResult(boolean accepted, Reason reason, String message) {
        static ValidationResult success() {
            return new ValidationResult(true, Reason.ACCESS_GRANTED, Reason.ACCESS_GRANTED.message);
        }

        static ValidationResult rejected(Reason reason, String message) {
            return new ValidationResult(false, reason, message);
        }
    }
}
