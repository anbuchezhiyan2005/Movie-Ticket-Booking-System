package resource;

import dto.request.TheatreRequest;
import dto.response.TheatreResponse;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import model.Theatre;
import service.TheatreService;
import util.RequestUsers;

import java.util.List;

@Path("/theatres")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TheatreResource {

    private final TheatreService theatreService;

    @Inject
    public TheatreResource(TheatreService theatreService) {
        this.theatreService = theatreService;
    }

    @GET
    public List<TheatreResponse> mine(@Context HttpServletRequest request) {
        Long adminId = RequestUsers.requireAdmin(request);
        return theatreService.getMyTheatres(adminId);
    }

    @POST
    public Response create(TheatreRequest body, @Context HttpServletRequest request) {
        Long adminId = RequestUsers.requireAdmin(request);
        Theatre theatre = theatreService.createTheatre(body, adminId);
        return Response.status(Response.Status.CREATED)
                .entity(theatreService.toResponse(theatre))
                .build();
    }

    @PUT
    @Path("/{id}")
    public Response update(@PathParam("id") Long id, TheatreRequest body, @Context HttpServletRequest request) {
        Long adminId = RequestUsers.requireAdmin(request);
        theatreService.updateTheatre(id, body, adminId);
        return Response.noContent().build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") Long id, @Context HttpServletRequest request) {
        Long adminId = RequestUsers.requireAdmin(request);
        theatreService.deleteTheatre(id, adminId);
        return Response.noContent().build();
    }
}
