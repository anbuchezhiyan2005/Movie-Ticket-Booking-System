package exception;

/*
 * Base class for all custom application exceptions.
 * It holds an HTTP status code to be returned in the API response.
 */
public class AppException extends RuntimeException {

    private final int status;

    public AppException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
