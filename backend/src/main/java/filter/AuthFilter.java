package filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.RequestUsers;
import util.RequestLogContext;
import enums.Role;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/*
 * Filter that intercepts incoming HTTP requests to handle authentication.
 * It allows public endpoints to pass through and checks session data for protected ones.
 */
public class AuthFilter implements Filter {

    private static final Logger LOGGER = Logger.getLogger(AuthFilter.class.getName());

    // Session attribute keys
    public static final String USER_ID = "userId";
    public static final String ROLE = "role";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String requestId = RequestLogContext.start();
        httpResponse.setHeader("X-Request-Id", requestId);
        long started = System.nanoTime();

        try {
            doFilterInternal(httpRequest, httpResponse, chain);
        } finally {
            Object userId = httpRequest.getAttribute(USER_ID);
            LOGGER.log(Level.INFO,
                    "event=http.completed requestId={0} userId={1} method={2} path={3} status={4} durationMs={5}",
                    new Object[]{requestId, userId == null ? "anonymous" : userId,
                            httpRequest.getMethod(), resolveRequestPath(httpRequest), httpResponse.getStatus(),
                            (System.nanoTime() - started) / 1_000_000L});
            RequestLogContext.clear();
        }
    }

    private void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        request.setCharacterEncoding("UTF-8");
        response.setCharacterEncoding("UTF-8");

        HttpServletRequest httpRequest = request;
        HttpServletResponse httpResponse = response;

        httpResponse.setHeader("Content-Security-Policy", "frame-ancestors 'self'");
        httpResponse.setHeader("X-Frame-Options", "DENY");

        if (isScannerRequest(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        // REWORK
        // Skip authentication check for OPTIONS requests or public endpoints
        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())
            || (isPublic(httpRequest) && !isAuthenticatedOAuthCallback(httpRequest))) {
            chain.doFilter(request, response);
            return;
        }

        // Check if a valid session exists with a user ID and recognized role
        HttpSession session = httpRequest.getSession(false);
        Object userId = session == null ? null : session.getAttribute(USER_ID);
        Object role = session == null ? null : session.getAttribute(ROLE);
        if (!(userId instanceof Number) || !(role instanceof String) || !isValidRole((String) role)) {
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"Not authenticated\"}");
            return;
        }

        if (isStateChangingRequest(httpRequest)) {
            String expectedToken = RequestUsers.requireCsrfToken(httpRequest);
            String suppliedToken = httpRequest.getHeader("X-CSRF-Token");
            if (expectedToken == null || suppliedToken == null || !expectedToken.equals(suppliedToken)) {
                httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
                httpResponse.setContentType("application/json");
                httpResponse.getWriter().write("{\"error\":\"Invalid CSRF token\"}");
                return;
            }
        }

        // Attach user info to the request attributes so downstream resources/controllers can access them easily
        httpRequest.setAttribute(USER_ID, ((Number) userId).longValue());
        httpRequest.setAttribute(ROLE, role);
        chain.doFilter(request, response);
    }

    private boolean isValidRole(String role) {
        try {
            Role.valueOf(role);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isStateChangingRequest(HttpServletRequest request) {
        String method = request.getMethod();
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }

    private boolean isAuthenticatedOAuthCallback(HttpServletRequest request) {
        String path = resolveRequestPath(request);
        if (!"GET".equalsIgnoreCase(request.getMethod())
                || !("/auth/google/callback".equals(path) || "/auth/twitter/callback".equals(path))) {
            return false;
        }
        HttpSession session = request.getSession(false);
        Object userId = session == null ? null : session.getAttribute(USER_ID);
        Object role = session == null ? null : session.getAttribute(ROLE);
        return userId instanceof Number && role instanceof String && isValidRole((String) role);
    }

    /*
     * Checks if the requested endpoint is public (does not require authentication).
     * This is intentionally tolerant of root and static-resource requests because the filter
     * now wraps every URL pattern and has to protect the HTML shell as well as the API.
     */
    private boolean isPublic(HttpServletRequest request) {
        String path = resolveRequestPath(request);
        String method = request.getMethod();

        if ("GET".equalsIgnoreCase(method)) {
            if (path.isEmpty() || "/".equals(path) || "/index.html".equals(path)) {
                return true;
            }
            if (path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".html")
                    || path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg")
                    || path.endsWith(".svg") || path.endsWith(".ico")) {
                return true;
            }
            if ("/health".equals(path)) {
                return true;
            }
            if ("/movies".equals(path) || path.matches("/movies/\\d+") || path.matches("/movies/\\d+/theatres")) {
                return true;
            }
            if ("/shows".equals(path) || path.matches("/shows/\\d+/seats") || path.matches("/screens/\\d+/shows")) {
                return true;
            }
            if ("/auth/google/start".equals(path) || "/auth/google/callback".equals(path)
                    || "/auth/twitter/start".equals(path) || "/auth/twitter/callback".equals(path)) {
                return true;
            }
        }

        if ("POST".equalsIgnoreCase(method) && ("/auth/register".equals(path) || "/auth/register-admin".equals(path) || "/auth/login".equals(path)
            || isScannerRedeemPath(request, path))) {
            return true;
        }

        return false;
    }

    private boolean isScannerRedeemPath(HttpServletRequest request, String path) {
        return "/scanner/tickets/redeem".equals(path)
                || "/api/scanner/tickets/redeem".equals(path)
                || (request.getRequestURI() != null
                && request.getRequestURI().contains("/scanner/tickets/redeem"));
    }

    private boolean isScannerRequest(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && request.getHeader("X-Scanner-API-Key") != null;
    }

    // NEED TO REFACTOR
    private String resolveRequestPath(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path == null || path.isBlank()) {
            path = request.getServletPath();
        }
        if (path == null || path.isBlank()) {
            String requestUri = request.getRequestURI();
            String contextPath = request.getContextPath();
            if (requestUri != null && contextPath != null && !contextPath.isBlank()) {
                requestUri = requestUri.substring(contextPath.length());
            }
            if (requestUri == null || requestUri.isBlank() || "/".equals(requestUri)) {
                path = "/";
            } else {
                path = requestUri;
            }
        }
        return path == null ? "" : path;
    }
}
