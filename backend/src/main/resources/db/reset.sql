-- Destructive development reset. Run manually against movie_booking only.
-- Back up the database before running this script.

USE movie_booking;

SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE booking_gate_tokens;
TRUNCATE TABLE otp_challenges;
TRUNCATE TABLE show_seats;
TRUNCATE TABLE bookings;
TRUNCATE TABLE shows;
TRUNCATE TABLE screens;
TRUNCATE TABLE theatres;
TRUNCATE TABLE user_identities;
TRUNCATE TABLE users;
TRUNCATE TABLE movies;

SET FOREIGN_KEY_CHECKS = 1;

INSERT INTO movies (movie_name, certification, description, director, duration_minutes, ticket_price) VALUES
('Inception', 'U/A', 'A thief who steals corporate secrets through dream-sharing technology.', 'Christopher Nolan', 148, 250),
('The Dark Knight', 'U/A', 'Batman faces the Joker in Gotham City.', 'Christopher Nolan', 152, 250),
('Interstellar', 'U/A', 'A team of explorers travel through a wormhole in space.', 'Christopher Nolan', 169, 300),
('Dune', 'U/A', 'Paul Atreides travels to the most dangerous planet in the universe.', 'Denis Villeneuve', 155, 280),
('Spider-Man: Across the Spider-Verse', 'U', 'Miles Morales catapults across the Multiverse.', 'Joaquim Dos Santos', 140, 220);