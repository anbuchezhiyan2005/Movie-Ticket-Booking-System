package util;

import enums.Role;
import exception.ForbiddenException;
import exception.UnauthorizedException;
import filter.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.security.SecureRandom;
import java.util.Base64;

/*
 * Utility class to manage user session and access control based on user identity.
 */
public final class RequestUsers {

    public static final String CSRF_TOKEN = "csrfToken";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private RequestUsers() {
    }

    // Retrieves the current user's ID from the request
    public static Long requireUserId(HttpServletRequest request) {
        Object value = request.getAttribute(AuthFilter.USER_ID);
        if (value instanceof Long userId) {
            return userId;
        }
        throw new UnauthorizedException("Not authenticated");
    }

    // Retrieves the current user's role from the request
    public static Role requireRole(HttpServletRequest request) {
        Object value = request.getAttribute(AuthFilter.ROLE);
        if (value instanceof String roleName) {
            try {
                return Role.valueOf(roleName);
            } catch (IllegalArgumentException e) {
                throw new UnauthorizedException("Not authenticated");
            }
        }
        throw new UnauthorizedException("Not authenticated");
    }

    // Ensures the user has a 'CUSTOMER' role
    public static Long requireCustomer(HttpServletRequest request) {
        Long userId = requireUserId(request);
        if (requireRole(request) != Role.CUSTOMER) {
            throw new ForbiddenException("Customer role required");
        }
        return userId;
    }

    // Ensures the user has an 'ADMIN' role
    public static Long requireAdmin(HttpServletRequest request) {
        Long userId = requireUserId(request);
        if (requireRole(request) != Role.ADMIN) {
            throw new ForbiddenException("Admin role required");
        }
        return userId;
    }

    // Creates a new session and stores user credentials
    public static void login(HttpServletRequest request, Long userId, Role role) {
        HttpSession existingSession = request.getSession(false);
        if (existingSession != null) {
            request.changeSessionId();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(AuthFilter.USER_ID, userId);
        session.setAttribute(AuthFilter.ROLE, role.name());
        session.setAttribute(CSRF_TOKEN, generateSecureToken());
    }

    public static String requireCsrfToken(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object token = session.getAttribute(CSRF_TOKEN);
        return token instanceof String csrfToken ? csrfToken : null;
    }

    public static String issueCsrfToken(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            session = request.getSession(true);
        }
        String token = generateSecureToken();
        session.setAttribute(CSRF_TOKEN, token);
        return token;
    }

    // Invalidates the current user session
    public static void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    private static String generateSecureToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
