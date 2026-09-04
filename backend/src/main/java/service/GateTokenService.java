package service;

import config.EnvironmentConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import repository.BookingGateTokenRepository;
import util.HmacUtil;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Singleton
public class GateTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String TOKEN_PATH = "/scanner/tickets/redeem";

    private final BookingGateTokenRepository tokenRepository;
    private final String hmacSecret;
    private final String scannerBaseUrl;

    @Inject
    public GateTokenService(BookingGateTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
        this.hmacSecret = EnvironmentConfig.get("QR_HMAC_SECRET");
        this.scannerBaseUrl = EnvironmentConfig.get("SCANNER_BASE_URL",
            "http://localhost:8080/movie-booking");
    }

    public String issue(Long bookingId, LocalDateTime expiresAt) {
        if (hmacSecret == null || hmacSecret.isBlank()) {
            throw new IllegalStateException("QR_HMAC_SECRET is not configured");
        }

        byte[] tokenBytes = new byte[32];
        SECURE_RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String payload = "t=" + token;
        String signature = HmacUtil.sign(payload, hmacSecret);
        tokenRepository.save(bookingId, HmacUtil.sha256(token), expiresAt);
        return scannerBaseUrl + TOKEN_PATH + "?" + payload + "&sig=" + signature;
    }
}
