package resource;

import enums.Role;
import exception.ForbiddenException;
import exception.UnauthorizedException;
import filter.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/*
 * Utility class to manage user session and access control based on user identity.
 */
public final class RequestUsers {

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
        HttpSession session = request.getSession(true);
        session.setAttribute(AuthFilter.USER_ID, userId);
        session.setAttribute(AuthFilter.ROLE, role.name());
    }

    // Invalidates the current user session
    public static void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
