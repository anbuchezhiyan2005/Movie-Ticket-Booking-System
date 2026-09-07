package resource;

import dto.request.LoginRequest;
import dto.request.RegisterRequest;
import dto.response.AuthResponse;
import enums.AuthProvider;
import enums.OAuthIntent;
import config.OAuthConfiguration;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import model.User;
import service.AuthService;
import service.OAuthProviderService;
import util.RequestUsers;

import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

/*
 * REST resource handling authentication endpoints (register, login, logout, and current user info).
 */
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    private static final Logger LOGGER = Logger.getLogger(AuthResource.class.getName());

    private final AuthService authService;
    private final OAuthProviderService oauthProviderService;

    @Inject
    public AuthResource(AuthService authService, OAuthProviderService oauthProviderService) {
        this.authService = authService;
        this.oauthProviderService = oauthProviderService;
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

    @GET
    @Path("/google/start")
    public Response googleStart(@Context HttpServletRequest httpRequest) {
        LOGGER.info("OAuth sign-in started provider=GOOGLE");
        return Response.seeOther(URI.create(
                oauthProviderService.authorizationUrl(httpRequest, AuthProvider.GOOGLE))).build();
    }

    @GET
    @Path("/google/callback")
    public Response googleCallback(@QueryParam("code") String code,
                                   @QueryParam("state") String state,
                                   @QueryParam("error") String error,
                                   @QueryParam("error_description") String errorDescription,
                                   @Context HttpServletRequest httpRequest) {
        return completeOAuth(AuthProvider.GOOGLE, code, state, error, errorDescription, httpRequest);
    }

    @GET
    @Path("/twitter/start")
    public Response twitterStart(@Context HttpServletRequest httpRequest) {
        LOGGER.info("OAuth sign-in started provider=TWITTER");
        return Response.seeOther(URI.create(
                oauthProviderService.authorizationUrl(httpRequest, AuthProvider.TWITTER))).build();
    }

    @GET
    @Path("/twitter/callback")
    public Response twitterCallback(@QueryParam("code") String code,
                                    @QueryParam("state") String state,
                                    @QueryParam("error") String error,
                                    @QueryParam("error_description") String errorDescription,
                                    @Context HttpServletRequest httpRequest) {
        return completeOAuth(AuthProvider.TWITTER, code, state, error, errorDescription, httpRequest);
    }

    @GET
    @Path("/google/link/start")
    public Response googleLinkStart(@Context HttpServletRequest httpRequest) {
        Long userId = RequestUsers.requireCustomer(httpRequest);
        return Response.seeOther(URI.create(oauthProviderService.authorizationUrl(
                httpRequest, AuthProvider.GOOGLE, OAuthIntent.LINK, userId))).build();
    }

    @GET
    @Path("/twitter/link/start")
    public Response twitterLinkStart(@Context HttpServletRequest httpRequest) {
        Long userId = RequestUsers.requireCustomer(httpRequest);
        return Response.seeOther(URI.create(oauthProviderService.authorizationUrl(
                httpRequest, AuthProvider.TWITTER, OAuthIntent.LINK, userId))).build();
    }

    @GET
    @Path("/google/link/callback")
    public Response googleLinkCallback(@QueryParam("code") String code,
                                       @QueryParam("state") String state,
                                       @Context HttpServletRequest httpRequest) {
        return completeOAuth(AuthProvider.GOOGLE, code, state, null, null, httpRequest);
    }

    @GET
    @Path("/twitter/link/callback")
    public Response twitterLinkCallback(@QueryParam("code") String code,
                                        @QueryParam("state") String state,
                                        @Context HttpServletRequest httpRequest) {
        return completeOAuth(AuthProvider.TWITTER, code, state, null, null, httpRequest);
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

    private Response completeOAuth(AuthProvider provider, String code, String state,
                                   String providerError, String providerErrorDescription,
                                   HttpServletRequest httpRequest) {
        if (providerError != null && !providerError.isBlank()) {
            LOGGER.warning("OAuth provider denied callback provider=" + provider
                    + " error=" + providerError
                    + " descriptionPresent=" + (providerErrorDescription != null));
            return oauthErrorRedirect("oauth_denied");
        }
        try {
            OAuthProviderService.CallbackResult callback = oauthProviderService.callback(
                    httpRequest, provider, code, state);
            User user;
            if (callback.transaction().intent() == OAuthIntent.LINK) {
                Long currentUserId = RequestUsers.requireCustomer(httpRequest);
                if (!currentUserId.equals(callback.transaction().userId())) {
                    throw new SecurityException("OAuth link transaction user mismatch");
                }
                user = authService.linkSocial(currentUserId, provider, callback.profile());
            } else {
                user = authService.authenticateSocial(provider, callback.profile());
                RequestUsers.login(httpRequest, user.getId(), user.getRole());
            }
                LOGGER.info("OAuth callback succeeded provider=" + provider
                    + " intent=" + callback.transaction().intent());
            return Response.seeOther(URI.create(OAuthConfiguration.successRedirectUri())).build();
        } catch (RuntimeException exception) {
                LOGGER.log(Level.WARNING,
                    "OAuth callback failed provider=" + provider
                        + " requestUri=" + httpRequest.getRequestURI()
                        + " codePresent=" + (code != null && !code.isBlank())
                        + " statePresent=" + (state != null && !state.isBlank()),
                    exception);
            String errorCode = exception.getMessage() != null
                    && exception.getMessage().contains("already")
                    ? "account_conflict" : "oauth_failed";
                return oauthErrorRedirect(errorCode);
        }
    }

            private Response oauthErrorRedirect(String errorCode) {
            return Response.seeOther(UriBuilder.fromUri(OAuthConfiguration.errorRedirectUri())
                .queryParam("oauth_error", errorCode)
                .build()).build();
            }
}
