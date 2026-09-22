package util;

import java.util.UUID;

/** Holds correlation data for the current request thread. */
public final class RequestLogContext {

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    private RequestLogContext() {
    }

    public static String start() {
        String requestId = "REQ-" + UUID.randomUUID().toString().replace("-", "");
        REQUEST_ID.set(requestId);
        return requestId;
    }

    public static String requestId() {
        String requestId = REQUEST_ID.get();
        return requestId == null ? "REQ-unknown" : requestId;
    }

    public static void clear() {
        REQUEST_ID.remove();
    }
}
