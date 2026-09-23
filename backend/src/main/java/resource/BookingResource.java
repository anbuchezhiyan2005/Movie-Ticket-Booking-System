package resource;

import dto.response.BookingResponse;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import service.BookingService;
import util.RequestUsers;

import java.sql.SQLException;
import java.util.List;

// The BookingResource class provides REST endpoints for managing bookings, including retrieving, and canceling bookings.
@Path("/bookings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BookingResource {

    private final BookingService bookingService;

    @Inject
    public BookingResource(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @GET
    public List<BookingResponse> mine(@Context HttpServletRequest request) throws SQLException {
        Long customerId = RequestUsers.requireCustomer(request);
        return bookingService.getMyBookings(customerId);
    }
}
