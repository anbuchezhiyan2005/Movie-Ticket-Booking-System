package resource;

import dto.request.BookingRequest;
import dto.response.BookingResponse;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import service.BookingService;
import util.RequestUsers;

import java.sql.SQLException;
import java.util.List;

// The BookingResource class provides REST endpoints for managing bookings, including creating, retrieving, and canceling bookings.
@Path("/bookings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BookingResource {

    private final BookingService bookingService;

    @Inject
    public BookingResource(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @POST
    public Response create(BookingRequest body, @Context HttpServletRequest request) throws SQLException {
        Long customerId = RequestUsers.requireCustomer(request);
        BookingResponse created = bookingService.bookTickets(customerId, body);
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    @GET
    public List<BookingResponse> mine(@Context HttpServletRequest request) throws SQLException {
        Long customerId = RequestUsers.requireCustomer(request);
        return bookingService.getMyBookings(customerId);
    }

    @GET
    @Path("/{id}")
    public BookingResponse get(@PathParam("id") Long id, @Context HttpServletRequest request) {
        Long customerId = RequestUsers.requireCustomer(request);
        return bookingService.getBooking(id, customerId);
    }

    @POST
    @Path("/{id}/cancel")
    public Response cancel(@PathParam("id") Long id, @Context HttpServletRequest request) throws SQLException {
        Long customerId = RequestUsers.requireCustomer(request);
        bookingService.cancelBooking(id, customerId);
        return Response.noContent().build();
    }
}
