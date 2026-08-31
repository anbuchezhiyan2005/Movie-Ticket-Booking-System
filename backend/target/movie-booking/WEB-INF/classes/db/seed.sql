USE movie_booking;

-- Movies are catalogue/seed data. Admins do not create movies via the API.
INSERT INTO movies (movie_name, certification, description, director, duration_minutes, ticket_price) VALUES
('Inception', 'U/A', 'A thief who steals corporate secrets through dream-sharing technology.', 'Christopher Nolan', 148, 250),
('The Dark Knight', 'U/A', 'Batman faces the Joker in Gotham City.', 'Christopher Nolan', 152, 250),
('Interstellar', 'U/A', 'A team of explorers travel through a wormhole in space.', 'Christopher Nolan', 169, 300),
('Dune', 'U/A', 'Paul Atreides travels to the most dangerous planet in the universe.', 'Denis Villeneuve', 155, 280),
('Spider-Man: Across the Spider-Verse', 'U', 'Miles Morales catapults across the Multiverse.', 'Joaquim Dos Santos', 140, 220);
