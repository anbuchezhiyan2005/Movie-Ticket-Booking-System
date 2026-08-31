package exception;

/*
 * Thrown when a request conflicts with the current server state (e.g., duplicate email).
 * Returns HTTP 409.
 */
public class ConflictException extends AppException {

    public ConflictException(String message) {
        super(409, message);
    }
}
