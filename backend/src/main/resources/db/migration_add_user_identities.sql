USE movie_booking;

ALTER TABLE users
    MODIFY COLUMN password_hash VARCHAR(255) NULL;

CREATE TABLE IF NOT EXISTS user_identities (
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