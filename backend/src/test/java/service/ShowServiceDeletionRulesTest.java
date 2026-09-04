package service;

import dto.request.ShowRequest;
import enums.BookingStatus;
import exception.ConflictException;
import model.Booking;
import model.Movie;
import model.Screen;
import model.Show;
import model.Theatre;
import org.junit.jupiter.api.Test;
import repository.BookingRepository;
import repository.MovieRepository;
import repository.ScreenRepository;
import repository.ShowRepository;
import repository.TheatreRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ShowServiceDeletionRulesTest {

    @Test
    void deleteShowIsTemporarilyDisabled() {
        ShowService service = new ShowService(
                new FakeShowRepository(LocalDateTime.now().minusHours(3)),
                new FakeMovieRepository(),
                new FakeScreenRepository(),
                new FakeTheatreRepository(),
                new FakeBookingRepository());

        ConflictException ex = assertThrows(ConflictException.class, () -> service.deleteShow(10L, 99L));
        assertEquals("Show deletion is temporarily disabled", ex.getMessage());
    }

    @Test
    void adminShowListingsExcludeShowsThatHaveEnded() {
        FakeShowRepository showRepository = new FakeShowRepository(List.of(
                show(LocalDateTime.now().minusHours(3)),
                show(LocalDateTime.now().plusHours(2))));
        ShowService service = new ShowService(
                showRepository,
                new FakeMovieRepository(),
                new FakeScreenRepository(),
                new FakeTheatreRepository(),
                new FakeBookingRepository());

        assertEquals(1, service.getShowsForScreen(7L, 99L).size());
        assertEquals(LocalDateTime.now().plusHours(2).getHour(),
                service.getShowsForScreen(7L, 99L).get(0).getShowTiming().getHour());
    }

            @Test
            void updateShowAllowsCancelledBookingsBeforeShowStarts() {
            FakeBookingRepository bookingRepository = new FakeBookingRepository();
            bookingRepository.addBooking(10L, BookingStatus.CANCELLED);
            ShowService service = new ShowService(
                new FakeShowRepository(LocalDateTime.now().plusHours(2)),
                new FakeMovieRepository(),
                new FakeScreenRepository(),
                new FakeTheatreRepository(),
                bookingRepository);

            assertDoesNotThrow(() -> service.updateShow(10L, showRequest(), 99L));
            }

            @Test
            void updateShowRejectsConfirmedBookings() {
            FakeBookingRepository bookingRepository = new FakeBookingRepository();
            bookingRepository.addBooking(10L, BookingStatus.CONFIRMED);
            ShowService service = new ShowService(
                new FakeShowRepository(LocalDateTime.now().plusHours(2)),
                new FakeMovieRepository(),
                new FakeScreenRepository(),
                new FakeTheatreRepository(),
                bookingRepository);

            ConflictException ex = assertThrows(ConflictException.class,
                () -> service.updateShow(10L, showRequest(), 99L));
            assertTrue(ex.getMessage().contains("confirmed bookings"));
            }

            @Test
            void updateShowRejectsShowsThatHaveStarted() {
            ShowService service = new ShowService(
                new FakeShowRepository(LocalDateTime.now().minusMinutes(1)),
                new FakeMovieRepository(),
                new FakeScreenRepository(),
                new FakeTheatreRepository(),
                new FakeBookingRepository());

            ConflictException ex = assertThrows(ConflictException.class,
                () -> service.updateShow(10L, showRequest(), 99L));
            assertTrue(ex.getMessage().contains("already started"));
            }

            private static ShowRequest showRequest() {
            ShowRequest request = new ShowRequest();
            request.setMovieId(5L);
            request.setScreenId(7L);
            request.setShowTiming(LocalDateTime.now().plusHours(4));
            return request;
            }

    private static Show show(LocalDateTime showTiming) {
        Show show = new Show();
        show.setShowId(10L);
        show.setMovieId(5L);
        show.setScreenId(7L);
        show.setShowTiming(showTiming);
        return show;
    }

    private static class FakeShowRepository extends ShowRepository {
        private final LocalDateTime showTiming;
        private final List<Show> shows;

        FakeShowRepository(LocalDateTime showTiming) {
            this.showTiming = showTiming;
            this.shows = List.of(show(showTiming));
        }

        FakeShowRepository(List<Show> shows) {
            this.showTiming = null;
            this.shows = shows;
        }

        @Override
        public Optional<Show> findById(Long showId) {
            return shows.stream().filter(show -> show.getShowId().equals(showId)).findFirst();
        }

        @Override
        public List<Show> findByScreenId(Long screenId) {
            return shows;
        }

        @Override
        public void update(Show show) {
        }

        @Override
        public void deleteById(Long showId) {
        }
    }

    private static class FakeMovieRepository extends MovieRepository {
        @Override
        public Optional<model.Movie> findById(Long movieId) {
            Movie movie = new Movie();
            movie.setMovieId(movieId);
            movie.setDurationInMinutes(120);
            return Optional.of(movie);
        }
    }

    private static class FakeScreenRepository extends ScreenRepository {
        @Override
        public Optional<Screen> findById(Long screenId) {
            Screen screen = new Screen();
            screen.setScreenId(screenId);
            screen.setTheatreId(15L);
            return Optional.of(screen);
        }
    }

    private static class FakeTheatreRepository extends TheatreRepository {
        @Override
        public Optional<Theatre> findById(Long theatreId) {
            Theatre theatre = new Theatre();
            theatre.setTheatreId(theatreId);
            theatre.setAdminId(99L);
            return Optional.of(theatre);
        }
    }

    private static class FakeBookingRepository extends BookingRepository {
        private final java.util.List<Booking> bookings = new java.util.ArrayList<>();

        void addBooking(Long showId, BookingStatus status) {
            Booking booking = new Booking();
            booking.setBookingId(System.nanoTime());
            booking.setShowId(showId);
            booking.setStatus(status);
            bookings.add(booking);
        }

        @Override
        public boolean existsByShowId(Long showId) {
            return bookings.stream().anyMatch(b -> b.getShowId().equals(showId));
        }

        @Override
        public boolean existsActiveBookingForShow(Long showId) {
            return bookings.stream().anyMatch(b -> b.getShowId().equals(showId)
                    && b.getStatus() != BookingStatus.CANCELLED
                    && b.getStatus() != BookingStatus.EXPIRED);
        }

        @Override
        public boolean existsConfirmedBookingForShow(Long showId) {
            return bookings.stream().anyMatch(b -> b.getShowId().equals(showId)
                    && b.getStatus() == BookingStatus.CONFIRMED);
        }
    }
}
