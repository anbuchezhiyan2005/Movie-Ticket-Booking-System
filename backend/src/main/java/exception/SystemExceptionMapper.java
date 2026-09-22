// DON'T KNOW WHY THIS EXISTS
package exception;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import util.RequestLogContext;

import java.util.logging.Level;
import java.util.logging.Logger;

import dto.response.ErrorResponse;

@Provider
public class SystemExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOGGER = Logger.getLogger(SystemExceptionMapper.class.getName());

    @Override
    public Response toResponse(Throwable throwable) {
        LOGGER.log(Level.SEVERE,
                "event=system.error requestId=" + RequestLogContext.requestId()
                        + " location=jersey.exception.mapper",
                throwable);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse("Internal server error"))
                .build();
    }
}