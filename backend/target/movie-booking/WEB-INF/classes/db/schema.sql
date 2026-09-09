-- Run after MySQL is installed, as a user that can create databases.
-- mysql -u root -p < src/main/resources/db/schema.sql

CREATE DATABASE IF NOT EXISTS movie_booking
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE movie_booking;

CREATE TABLE users (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    name            VARCHAR(100)  NOT NULL,
    email           VARCHAR(255)  NOT NULL,
    phone_number    VARCHAR(16)   NULL,
    password_hash   VARCHAR(255)  NULL,
    role            VARCHAR(20)   NOT NULL,
    wallet_balance  INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email),
    CONSTRAINT chk_users_role CHECK (role IN ('CUSTOMER', 'ADMIN'))
) ENGINE=InnoDB;

CREATE TABLE user_identities (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    provider          VARCHAR(20)  NOT NULL,
    provider_subject  VARCHAR(255) NOT NULL,
    provider_email    VARCHAR(255) NULL,
    display_name      VARCHAR(100) NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_identities_provider_subject (provider, provider_subject),
    UNIQUE KEY uk_user_identities_user_provider (user_id, provider),
    CONSTRAINT fk_user_identities_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_user_identities_provider CHECK (provider IN ('GOOGLE', 'TWITTER'))
) ENGINE=InnoDB;

CREATE INDEX idx_user_identities_user ON user_identities (user_id);

CREATE TABLE theatres (
    theatre_id       BIGINT       NOT NULL AUTO_INCREMENT,
    admin_id         BIGINT       NOT NULL,
    theatre_name     VARCHAR(150) NOT NULL,
    theatre_location VARCHAR(255) NOT NULL,
    PRIMARY KEY (theatre_id),
    CONSTRAINT fk_theatres_admin
        FOREIGN KEY (admin_id) REFERENCES users (id)
) ENGINE=InnoDB;

CREATE TABLE screens (
    screen_id     BIGINT       NOT NULL AUTO_INCREMENT,
    theatre_id    BIGINT       NOT NULL,
    screen_name   VARCHAR(100) NOT NULL,
    row_range     VARCHAR(20)  NOT NULL,
    seats_per_row INT          NOT NULL,
    PRIMARY KEY (screen_id),
    CONSTRAINT fk_screens_theatre
        FOREIGN KEY (theatre_id) REFERENCES theatres (theatre_id)
) ENGINE=InnoDB;

CREATE TABLE movies (
    movie_id          BIGINT       NOT NULL AUTO_INCREMENT,
    movie_name        VARCHAR(200) NOT NULL,
    certification     VARCHAR(10)  NOT NULL,
    description       TEXT,
    director          VARCHAR(150),
    duration_minutes  INT          NOT NULL,
    ticket_price      INT          NOT NULL,
    PRIMARY KEY (movie_id)
) ENGINE=InnoDB;

CREATE TABLE shows (
    show_id    BIGINT   NOT NULL AUTO_INCREMENT,
    movie_id   BIGINT   NOT NULL,
    screen_id  BIGINT   NOT NULL,
    start_time DATETIME  NOT NULL,
    PRIMARY KEY (show_id),
    CONSTRAINT fk_shows_movie
        FOREIGN KEY (movie_id) REFERENCES movies (movie_id),
    CONSTRAINT fk_shows_screen
        FOREIGN KEY (screen_id) REFERENCES screens (screen_id)
) ENGINE=InnoDB;

CREATE TABLE bookings (
    booking_id    BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       BIGINT      NOT NULL,
    show_id       BIGINT      NOT NULL,
    booking_time  DATETIME     NOT NULL,
    status        VARCHAR(20) NOT NULL,
    total_amount  INT         NOT NULL,
    expires_at    DATETIME     NULL,
    PRIMARY KEY (booking_id),
    CONSTRAINT fk_bookings_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_bookings_show
        FOREIGN KEY (show_id) REFERENCES shows (show_id),
    CONSTRAINT chk_bookings_status
        CHECK (status IN ('AWAITING_OTP', 'PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED'))
) ENGINE=InnoDB;

CREATE TABLE otp_challenges (
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
    CONSTRAINT fk_otp_challenges_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_otp_challenges_booking
        FOREIGN KEY (booking_id) REFERENCES bookings (booking_id) ON DELETE CASCADE,
    CONSTRAINT chk_otp_challenges_purpose
        CHECK (purpose IN ('BOOKING', 'CANCELLATION'))
) ENGINE=InnoDB;

CREATE INDEX idx_otp_challenges_lookup
    ON otp_challenges (user_id, purpose, booking_id, consumed_at, expires_at);

-- Sparse occupancy: a row exists only when a booking has claimed the seat.
CREATE TABLE show_seats (
    show_id     BIGINT     NOT NULL,
    row_label   VARCHAR(1) NOT NULL,
    seat_number INT        NOT NULL,
    booking_id  BIGINT     NOT NULL,
    PRIMARY KEY (show_id, row_label, seat_number),
    CONSTRAINT fk_show_seats_show
        FOREIGN KEY (show_id) REFERENCES shows (show_id),
    CONSTRAINT fk_show_seats_booking
        FOREIGN KEY (booking_id) REFERENCES bookings (booking_id)
) ENGINE=InnoDB;

CREATE TABLE booking_gate_tokens (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    booking_id         BIGINT       NOT NULL,
    token_hash         CHAR(64)     NOT NULL,
    status             VARCHAR(10)  NOT NULL DEFAULT 'ISSUED',
    expires_at         DATETIME     NOT NULL,
    scanned_at         DATETIME     NULL,
    scanned_by_device  VARCHAR(100) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_booking_gate_token_hash (token_hash),
    CONSTRAINT fk_gate_tokens_booking
        FOREIGN KEY (booking_id) REFERENCES bookings (booking_id) ON DELETE CASCADE,
    CONSTRAINT chk_booking_gate_token_status
        CHECK (status IN ('ISSUED', 'USED'))
) ENGINE=InnoDB;

CREATE INDEX idx_show_seats_booking ON show_seats (booking_id);
CREATE INDEX idx_shows_movie ON shows (movie_id);
CREATE INDEX idx_shows_screen ON shows (screen_id);
CREATE INDEX idx_bookings_user ON bookings (user_id);
CREATE INDEX idx_bookings_pending_expiry ON bookings (status, expires_at);
CREATE INDEX idx_booking_gate_tokens_lookup ON booking_gate_tokens (token_hash, status);
