package dto.request;

public record OtpVerifyRequest(
        String challengeToken,
        String code
) {}