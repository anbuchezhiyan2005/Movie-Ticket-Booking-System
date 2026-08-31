package resource;

import dto.request.ScreenRequest;
import dto.response.ScreenResponse;
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
import model.Screen;
import service.ScreenService;

import java.util.List;

@Path("/screens")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ScreenResource {

    private final ScreenService screenService;

    @Inject
    public ScreenResource(ScreenService screenService) {
        this.screenService = screenService;
    }

    @GET
    @Path("/theatre/{theatreId}")
    public List<ScreenResponse> byTheatre(
            @PathParam("theatreId") Long theatreId,
            @Context HttpServletRequest request) {
        Long adminId = RequestUsers.requireAdmin(request);
        return screenService.getScreensForTheatre(theatreId, adminId);
    }

    @POST
    public Response create(ScreenRequest body, @Context HttpServletRequest request) {
        Long adminId = RequestUsers.requireAdmin(request);
        Screen screen = screenService.createScreen(body, adminId);
        return Response.status(Response.Status.CREATED)
                .entity(screenService.toResponse(screen))
                .build();
    }

    @PUT
    @Path("/{id}")
    public Response update(@PathParam("id") Long id, ScreenRequest body, @Context HttpServletRequest request) {
        Long adminId = RequestUsers.requireAdmin(request);
        screenService.updateScreen(id, body, adminId);
        return Response.noContent().build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") Long id, @Context HttpServletRequest request) {
        Long adminId = RequestUsers.requireAdmin(request);
        screenService.deleteScreen(id, adminId);
        return Response.noContent().build();
    }
}
