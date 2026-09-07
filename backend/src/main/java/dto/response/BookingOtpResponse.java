package dto.response;

import java.time.LocalDateTime;

public record BookingOtpResponse(
        BookingResponse booking,
        String challengeToken,
        String destination,
        LocalDateTime expiresAt,
        LocalDateTime resendAvailableAt
) {}