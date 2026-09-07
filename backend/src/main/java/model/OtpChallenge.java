package model;

import enums.OtpPurpose;

import java.time.LocalDateTime;

public class OtpChallenge {

    private Long id;
    private Long userId;
    private Long bookingId;
    private OtpPurpose purpose;
    private String challengeTokenHash;
    private String otpHash;
    private LocalDateTime expiresAt;
    private int attemptCount;
    private int maxAttempts;
    private LocalDateTime lastSentAt;
    private LocalDateTime consumedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getBookingId() { return bookingId; }
    public void setBookingId(Long bookingId) { this.bookingId = bookingId; }
    public OtpPurpose getPurpose() { return purpose; }
    public void setPurpose(OtpPurpose purpose) { this.purpose = purpose; }
    public String getChallengeTokenHash() { return challengeTokenHash; }
    public void setChallengeTokenHash(String challengeTokenHash) { this.challengeTokenHash = challengeTokenHash; }
    public String getOtpHash() { return otpHash; }
    public void setOtpHash(String otpHash) { this.otpHash = otpHash; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public LocalDateTime getLastSentAt() { return lastSentAt; }
    public void setLastSentAt(LocalDateTime lastSentAt) { this.lastSentAt = lastSentAt; }
    public LocalDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(LocalDateTime consumedAt) { this.consumedAt = consumedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}