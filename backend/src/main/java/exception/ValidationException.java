package exception;

/*
 * Thrown when user input validation fails.
 * Returns HTTP 400.
 */
public class ValidationException extends AppException {

    public ValidationException(String message) {
        super(400, message);
    }
}
