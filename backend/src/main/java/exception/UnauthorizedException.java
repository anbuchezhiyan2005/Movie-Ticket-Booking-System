package exception;

/*
 * Thrown when a user is not authenticated or fails login.
 * Returns HTTP 401.
 */
public class UnauthorizedException extends AppException {

    public UnauthorizedException(String message) {
        super(401, message);
    }
}
