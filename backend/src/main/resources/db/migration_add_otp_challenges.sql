USE movie_booking;

ALTER TABLE bookings
    MODIFY COLUMN status VARCHAR(20) NOT NULL;

ALTER TABLE bookings
    DROP CHECK chk_bookings_status,
    ADD CONSTRAINT chk_bookings_status
        CHECK (status IN ('AWAITING_OTP', 'PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED'));

CREATE TABLE IF NOT EXISTS otp_challenges (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    user_id              BIGINT       NOT NULL,
    booking_id           BIGINT       NULL,
    purpose              VARCHAR(20)  NOT NULL,
    challenge_token_hash CHAR(64)     NOT NULL,
    otp_hash             CHAR(64)     NOT NULL,
    expires_at           DATETIME     NOT NULL,
    attempt_count        INT          NOT NULL DEFAULT 0,
    max_attempts         INT          NOT NULL DEFAULT 5,
    last_sent_at         DATETIME     NOT NULL,
    consumed_at          DATETIME     NULL,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_otp_challenges_token (challenge_token_hash),
    CONSTRAINT fk_otp_challenges_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_otp_challenges_booking FOREIGN KEY (booking_id) REFERENCES bookings (booking_id) ON DELETE CASCADE,
    CONSTRAINT chk_otp_challenges_purpose CHECK (purpose IN ('BOOKING', 'CANCELLATION'))
) ENGINE=InnoDB;

CREATE INDEX idx_otp_challenges_lookup
    ON otp_challenges (user_id, purpose, booking_id, consumed_at, expires_at);