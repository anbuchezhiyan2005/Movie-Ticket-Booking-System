package resource;

import dto.request.ShowRequest;
import dto.response.SeatResponse;
import dto.response.ShowResponse;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import model.Show;
import service.BookingService;
import service.ShowService;
import util.RequestUsers;

import java.util.List;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ShowResource {

    private final ShowService showService;
    private final BookingService bookingService;

    @Inject
    public ShowResource(ShowService showService, BookingService bookingService) {
        this.showService = showService;
        this.bookingService = bookingService;
    }

    // Customer-facing endpoints
    @GET
    @Path("/shows")
    public List<ShowResponse> byMovie(@QueryParam("movieId") Long movieId) {
        if (movieId == null) {
            throw new exception.ValidationException("movieId query parameter is required");
        }
        return showService.getShowsForMovie(movieId);
    }

    @GET
    @Path("/screens/{screenId}/shows")
    public List<ShowResponse> byScreen(@PathParam("screenId") Long screenId) {
        return showService.getShowsForScreen(screenId);
    }

    @GET
    @Path("/theatres/{theatreId}/shows")
    public List<ShowResponse> byTheatre(@PathParam("theatreId") Long theatreId, @Context HttpServletRequest request) {
        Long adminId = RequestUsers.requireAdmin(request);
        return showService.getShowsForTheatre(theatreId, adminId);
    }

    @GET
    @Path("/admin/screens/{screenId}/shows")
    public List<ShowResponse> adminByScreen(@PathParam("screenId") Long screenId, @Context HttpServletRequest request) {
        Long adminId = RequestUsers.requireAdmin(request);
        return showService.getShowsForScreen(screenId, adminId);
    }

    @GET
    @Path("/shows/{id}/seats")
    public List<SeatResponse> seats(@PathParam("id") Long showId) {
        return bookingService.getAvailableSeats(showId);
    }

    // Admin-facing endpoints
    @POST
    @Path("/shows")
    public Response create(ShowRequest body, @Context HttpServletRequest request) {
        Long adminId = RequestUsers.requireAdmin(request);
        Show show = showService.createShow(body, adminId);
        return Response.status(Response.Status.CREATED)
                .entity(showService.toShowResponse(show))
                .build();
    }

    @PUT
    @Path("/shows/{id}")
    public Response update(@PathParam("id") Long id, ShowRequest body, @Context HttpServletRequest request) {
        Long adminId = RequestUsers.requireAdmin(request);
        showService.updateShow(id, body, adminId);
        return Response.noContent().build();
    }

    @DELETE
    @Path("/shows/{id}")
    public Response delete(@PathParam("id") Long id, @Context HttpServletRequest request) {
        Long adminId = RequestUsers.requireAdmin(request);
        showService.deleteShow(id, adminId);
        return Response.noContent().build();
    }
}
