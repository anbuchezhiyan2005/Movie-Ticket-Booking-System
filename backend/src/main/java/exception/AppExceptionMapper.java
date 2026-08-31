package exception;

import dto.response.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class AppExceptionMapper implements ExceptionMapper<AppException> {

    @Override
    public Response toResponse(AppException exception) {
        ErrorResponse error = new ErrorResponse(exception.getMessage());
        return Response.status(exception.getStatus())
                .type(MediaType.APPLICATION_JSON)
                .entity(error)
                .build();
    }
}