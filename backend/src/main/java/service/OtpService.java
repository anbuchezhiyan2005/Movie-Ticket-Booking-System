package service;

import config.Database;
import config.EnvironmentConfig;
import enums.OtpPurpose;
import exception.ValidationException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import model.OtpChallenge;
import repository.OtpChallengeRepository;
import util.HmacUtil;
import util.RequestLogContext;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.logging.Logger;

@Singleton
public class OtpService {

    private static final Logger LOGGER = Logger.getLogger(OtpService.class.getName());
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration OTP_LIFETIME = Duration.ofMinutes(5);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpChallengeRepository repository;
    private final String hashSecret;

    @Inject
    public OtpService(OtpChallengeRepository repository) {
        this.repository = repository;
        this.hashSecret = EnvironmentConfig.get("OTP_HASH_SECRET", "development-only-otp-secret");
    }

    public Challenge create(Long userId, Long bookingId, OtpPurpose purpose) {
        if (userId == null || purpose == null) {
            throw new ValidationException("OTP challenge details are required");
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String token = randomToken();
        LocalDateTime now = LocalDateTime.now();
        OtpChallenge challenge = new OtpChallenge();
        challenge.setUserId(userId);
        challenge.setBookingId(bookingId);
        challenge.setPurpose(purpose);
        challenge.setChallengeTokenHash(HmacUtil.sha256(token));
        challenge.setOtpHash(HmacUtil.sign("otp:" + code, hashSecret));
        challenge.setExpiresAt(now.plus(OTP_LIFETIME));
        challenge.setLastSentAt(now);
        challenge.setMaxAttempts(MAX_ATTEMPTS);
        OtpChallenge saved = repository.save(challenge);
        LOGGER.info("event=otp.challenge.created requestId=" + RequestLogContext.requestId()
            + " purpose=" + purpose + " userId=" + userId + " bookingId=" + bookingId
            + " challengeId=" + saved.getId());
        return new Challenge(saved.getId(), token, code, saved.getExpiresAt(),
            saved.getLastSentAt().plus(RESEND_COOLDOWN));
    }

    public Challenge resend(Long userId, Long bookingId, OtpPurpose purpose) {
        OtpChallenge active = repository.findActive(userId, bookingId, purpose)
                .orElseThrow(() -> new ValidationException("No active OTP challenge to resend"));
        LocalDateTime now = LocalDateTime.now();
        if (active.getLastSentAt().plus(RESEND_COOLDOWN).isAfter(now)) {
            throw new ValidationException("Please wait before requesting another OTP");
        }
        // Supersede before creating a replacement so the old code cannot be accepted.
        repository.supersede(active.getId(), now);
        return create(userId, bookingId, purpose);
    }

    // ONLY FOR TESTING
    public void verifyCode(String token, Long userId, Long bookingId, OtpPurpose purpose, String code) {
        if (token == null || token.isBlank() || code == null || code.isBlank()) {
            throw new ValidationException("OTP challenge and code are required");
        }
        try {
                // Commit failed-attempt persistence before returning the validation error.
                boolean verified = Database.inTransaction(() ->
                    verifyCodeInTransaction(token, userId, bookingId, purpose, code));
            if (!verified) {
                throw new ValidationException("OTP is invalid");
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("Could not verify OTP", exception);
        }
    }

    public boolean verifyCodeInTransaction(String token, Long userId, Long bookingId,
                                           OtpPurpose purpose, String code) {
        // Can be included inside validateChallenge()
        if (token == null || token.isBlank() || code == null || code.isBlank()) {
            throw new ValidationException("OTP challenge and code are required");
        }
        OtpChallenge challenge = repository.findForUpdate(HmacUtil.sha256(token))
                .orElseThrow(() -> new ValidationException("OTP challenge is invalid"));
        validateChallenge(challenge, userId, bookingId, purpose);
        if (!HmacUtil.verify("otp:" + code, challenge.getOtpHash(), hashSecret)) {
            repository.incrementAttempts(challenge.getId());
                LOGGER.warning("event=otp.verification.failed requestId=" + RequestLogContext.requestId()
                    + " purpose=" + purpose
                    + " userId=" + userId + " bookingId=" + bookingId
                    + " challengeId=" + challenge.getId()
                    + " reason=invalid_code");
                    // Return after incrementing so standalone verification can commit the attempt.
                    return false;
        }
        repository.consume(challenge.getId(), LocalDateTime.now());
        LOGGER.info("event=otp.verification.succeeded requestId=" + RequestLogContext.requestId()
            + " purpose=" + purpose
                + " userId=" + userId + " bookingId=" + bookingId
                + " challengeId=" + challenge.getId());
            return true;
    }

    private void validateChallenge(OtpChallenge challenge, Long userId, Long bookingId, OtpPurpose purpose) {
        if (!userId.equals(challenge.getUserId()) || !purpose.equals(challenge.getPurpose())
                || (bookingId == null ? challenge.getBookingId() != null : !bookingId.equals(challenge.getBookingId()))) {
                LOGGER.warning("event=otp.verification.failed requestId=" + RequestLogContext.requestId()
                    + " purpose=" + purpose
                    + " userId=" + userId + " bookingId=" + bookingId
                    + " challengeId=" + challenge.getId() + " reason=scope_mismatch");
            throw new ValidationException("OTP challenge is invalid");
        }
        if (challenge.getConsumedAt() != null) {
            throw new ValidationException("OTP challenge was already used");
        }
        if (LocalDateTime.now().isAfter(challenge.getExpiresAt())) {
                LOGGER.warning("event=otp.verification.failed requestId=" + RequestLogContext.requestId()
                    + " purpose=" + purpose
                    + " userId=" + userId + " bookingId=" + bookingId
                    + " challengeId=" + challenge.getId() + " reason=expired");
            throw new ValidationException("OTP challenge has expired");
        }
        if (challenge.getAttemptCount() >= challenge.getMaxAttempts()) {
            throw new ValidationException("OTP attempt limit exceeded");
        }
    }

    // HELPER
    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // HELPER
    public record Challenge(Long id, String token, String code, LocalDateTime expiresAt,
                            LocalDateTime resendAvailableAt) {
    }
}