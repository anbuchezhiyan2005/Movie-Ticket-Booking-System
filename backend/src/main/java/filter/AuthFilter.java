package filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import enums.Role;

import java.io.IOException;

/*
 * Filter that intercepts incoming HTTP requests to handle authentication.
 * It allows public endpoints to pass through and checks session data for protected ones.
 */
public class AuthFilter implements Filter {

    // Session attribute keys
    public static final String USER_ID = "userId";
    public static final String ROLE = "role";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Skip authentication check for OPTIONS requests (CORS preflight) or public endpoints
        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod()) || isPublic(httpRequest)) {
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

    /*
     * Checks if the requested endpoint is public (does not require authentication).
     */
    private boolean isPublic(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path == null) {
            path = "";
        }
        String method = request.getMethod();

        // Health check is always public
        if ("GET".equalsIgnoreCase(method) && "/health".equals(path)) {
            return true;
        }
        // Register and login endpoints are public
        if ("POST".equalsIgnoreCase(method) && ("/auth/register".equals(path) || "/auth/login".equals(path))) {
            return true;
        }
        // General GET endpoints for movies, shows, and screens are public
        if ("GET".equalsIgnoreCase(method)) {
            if ("/movies".equals(path) || path.matches("/movies/\\d+") || path.matches("/movies/\\d+/theatres")) {
                return true;
            }
            if ("/shows".equals(path) || path.matches("/shows/\\d+/seats") || path.matches("/screens/\\d+/shows")) {
                return true;
            }
        }
        return false;
    }
}
