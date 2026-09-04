package resource;

import config.EnvironmentConfig;
import dto.request.GateRedeemRequest;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import service.GateValidationService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Path("/scanner/tickets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GateResource {

    private final GateValidationService gateValidationService;

    @Inject
    public GateResource(GateValidationService gateValidationService) {
        this.gateValidationService = gateValidationService;
    }

    @POST
    @Path("/redeem")
    public Response redeem(GateRedeemRequest request, @Context HttpServletRequest httpRequest) {
        String expectedKey = EnvironmentConfig.get("SCANNER_API_KEY");
        String suppliedKey = httpRequest.getHeader("X-Scanner-API-Key");
        if (expectedKey == null || expectedKey.isBlank() || suppliedKey == null
                || !MessageDigest.isEqual(expectedKey.getBytes(StandardCharsets.UTF_8),
                        suppliedKey.getBytes(StandardCharsets.UTF_8))) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ErrorResponse("Scanner authentication failed"))
                    .build();
        }
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("Token and signature are required"))
                    .build();
        }

        GateValidationService.ValidationResult result = gateValidationService.redeem(
                request.getToken(), request.getSignature(), request.getDeviceId());
        return Response.ok(result).build();
    }

    public record ErrorResponse(String error) {
    }
}