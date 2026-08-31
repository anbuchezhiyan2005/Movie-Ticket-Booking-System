package exception;

/*
 * Thrown when a requested resource (movie, user, etc.) is not found.
 * Returns HTTP 404.
 */
public class NotFoundException extends AppException {

    public NotFoundException(String message) {
        super(404, message);
    }
}
