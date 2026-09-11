package dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OtpChallengeResponse(
        Long challengeId,
        String challengeToken,
        String destination,
        LocalDateTime expiresAt,
        LocalDateTime resendAvailableAt,
        String simulationOtp
) {}