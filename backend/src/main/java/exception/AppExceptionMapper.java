// DON'T KNOW WHY THIS EXISTS

package exception;

import dto.response.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Context;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import util.RequestLogContext;

import java.util.logging.Level;
import java.util.logging.Logger;

@Provider
public class AppExceptionMapper implements ExceptionMapper<AppException> {

    private static final Logger LOGGER = Logger.getLogger(AppExceptionMapper.class.getName());

    @Context
    private HttpServletRequest request;

    @Override
    public Response toResponse(AppException exception) {
        LOGGER.log(Level.WARNING,
            "event=business.operation.failed requestId={0} userId={1} reason={2} status={3}",
            new Object[]{RequestLogContext.requestId(), currentUserId(), safeReason(exception), exception.getStatus()});
        ErrorResponse error = new ErrorResponse(exception.getMessage());
        return Response.status(exception.getStatus())
                .type(MediaType.APPLICATION_JSON)
                .entity(error)
                .build();
    }

    private String safeReason(AppException exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName()
                : message.replaceAll("\\s+", "_");
    }

    private Object currentUserId() {
        if (request == null) {
            return "anonymous";
        }
        Object userId = request.getAttribute(filter.AuthFilter.USER_ID);
        return userId == null ? "anonymous" : userId;
    }
}