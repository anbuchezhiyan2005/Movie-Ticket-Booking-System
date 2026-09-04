USE movie_booking;

CREATE TABLE IF NOT EXISTS booking_gate_tokens (
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

CREATE INDEX idx_booking_gate_tokens_lookup ON booking_gate_tokens (token_hash, status);