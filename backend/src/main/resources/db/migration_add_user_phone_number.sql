USE movie_booking;

ALTER TABLE users
    ADD COLUMN phone_number VARCHAR(16) NULL AFTER email;