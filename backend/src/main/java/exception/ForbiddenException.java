package exception;

/*
 * Thrown when a user does not have permission to perform an action (wrong role, etc.).
 * Returns HTTP 403.
 */
public class ForbiddenException extends AppException {

    public ForbiddenException(String message) {
        super(403, message);
    }
}
