package dto.response;

import java.time.LocalDateTime;

public record OtpChallengeResponse(
        Long challengeId,
        String challengeToken,
        String destination,
        LocalDateTime expiresAt,
        LocalDateTime resendAvailableAt
) {}