package service;

import dto.request.TheatreRequest;
import enums.BookingStatus;
import exception.ConflictException;
import model.Booking;
import model.Theatre;
import org.junit.jupiter.api.Test;
import repository.BookingRepository;
import repository.MovieRepository;
import repository.ScreenRepository;
import repository.ShowRepository;
import repository.TheatreRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TheatreServiceUpdateRulesTest {

    @Test
    void updateTheatreAllowsCancelledBookingsAndUnfinishedShows() {
        FakeBookingRepository bookings = new FakeBookingRepository();
        bookings.addBooking(BookingStatus.CANCELLED);

        TheatreService service = new TheatreService(
                new FakeTheatreRepository(),
                new FakeScreenRepository(),
                new ShowRepository(),
                new MovieRepository(),
                bookings);

        assertDoesNotThrow(() -> service.updateTheatre(15L, request(), 99L));
    }

    @Test
    void updateTheatreRejectsConfirmedBookings() {
        FakeBookingRepository bookings = new FakeBookingRepository();
        bookings.addBooking(BookingStatus.CONFIRMED);

        TheatreService service = new TheatreService(
                new FakeTheatreRepository(),
                new FakeScreenRepository(),
                new ShowRepository(),
                new MovieRepository(),
                bookings);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.updateTheatre(15L, request(), 99L));
        assertTrue(ex.getMessage().contains("confirmed bookings"));
    }

    private static TheatreRequest request() {
        TheatreRequest request = new TheatreRequest();
        request.setTheatreName("Updated Theatre");
        request.setTheatreLocation("Updated Location");
        return request;
    }

    private static class FakeTheatreRepository extends TheatreRepository {
        @Override
        public Optional<Theatre> findById(Long theatreId) {
            Theatre theatre = new Theatre();
            theatre.setTheatreId(theatreId);
            theatre.setAdminId(99L);
            return Optional.of(theatre);
        }

        @Override
        public void update(Theatre theatre) {
        }
    }

    private static class FakeScreenRepository extends ScreenRepository {
        @Override
        public List<model.Screen> findByTheatreId(Long theatreId) {
            return List.of();
        }
    }

    private static class FakeBookingRepository extends BookingRepository {
        private final List<Booking> bookings = new ArrayList<>();

        void addBooking(BookingStatus status) {
            Booking booking = new Booking();
            booking.setShowId(10L);
            booking.setStatus(status);
            bookings.add(booking);
        }

        @Override
        public boolean existsConfirmedBookingForTheatre(Long theatreId) {
            return bookings.stream().anyMatch(booking -> booking.getStatus() == BookingStatus.CONFIRMED);
        }
    }
}