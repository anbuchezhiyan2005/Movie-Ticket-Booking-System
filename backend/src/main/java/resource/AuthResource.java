package resource;

import dto.request.LoginRequest;
import dto.request.RegisterRequest;
import dto.response.AuthResponse;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import model.User;
import service.AuthService;
import util.RequestUsers;

/*
 * REST resource handling authentication endpoints (register, login, logout, and current user info).
 */
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    private final AuthService authService;

    @Inject
    public AuthResource(AuthService authService) {
        this.authService = authService;
    }

    // Registers a new user
    @POST
    @Path("/register")
    public Response register(RegisterRequest request, @Context HttpServletRequest httpRequest) {
        User user = authService.register(request);
        AuthResponse response = authService.toAuthResponse(user);
        response.setCsrfToken(RequestUsers.issueCsrfToken(httpRequest));
        return Response.status(Response.Status.CREATED)
                .entity(response)
                .build();
    }

    @POST
    @Path("/register-admin")
    public Response registerAdmin(RegisterRequest request, @Context HttpServletRequest httpRequest) {
        User user = authService.registerAdmin(request);
        AuthResponse response = authService.toAuthResponse(user);
        response.setCsrfToken(RequestUsers.issueCsrfToken(httpRequest));
        return Response.status(Response.Status.CREATED)
                .entity(response)
                .build();
    }

    // Logs in a user and starts a session
    @POST
    @Path("/login")
    public AuthResponse login(LoginRequest request, @Context HttpServletRequest httpRequest) {
        User user = authService.login(request);
        RequestUsers.login(httpRequest, user.getId(), user.getRole());
        AuthResponse authResponse = authService.toAuthResponse(user);
        authResponse.setCsrfToken(RequestUsers.requireCsrfToken(httpRequest));
        return authResponse;
    }

    // Logs out the current user by invalidating their session
    @POST
    @Path("/logout")
    public Response logout(@Context HttpServletRequest httpRequest) {
        RequestUsers.logout(httpRequest);
        return Response.noContent().build();
    }

    // Returns the profile information of the currently authenticated user
    @GET
    @Path("/me")
    public AuthResponse me(@Context HttpServletRequest httpRequest) {
        Long userId = RequestUsers.requireUserId(httpRequest);
        AuthResponse authResponse = authService.toAuthResponse(authService.getById(userId));
        authResponse.setCsrfToken(RequestUsers.requireCsrfToken(httpRequest));
        return authResponse;
    }
}
