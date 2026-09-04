package resource;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ShowResourceRouteTest {

    @Test
    void customerAndAdminShowEndpointsUseClearNonConflictingPaths() throws Exception {
        Path classPath = ShowResource.class.getAnnotation(Path.class);
        assertNotNull(classPath);
        assertEquals("/", classPath.value());

        assertRoute(ShowResource.class.getDeclaredMethod("byMovie", Long.class), GET.class, "/shows");
        assertRoute(ShowResource.class.getDeclaredMethod("byScreen", Long.class), GET.class, "/screens/{screenId}/shows");
        assertRoute(ShowResource.class.getDeclaredMethod("byTheatre", Long.class, jakarta.servlet.http.HttpServletRequest.class), GET.class, "/theatres/{theatreId}/shows");
        assertRoute(ShowResource.class.getDeclaredMethod("adminByScreen", Long.class, jakarta.servlet.http.HttpServletRequest.class), GET.class, "/admin/screens/{screenId}/shows");
        assertRoute(ShowResource.class.getDeclaredMethod("seats", Long.class), GET.class, "/shows/{id}/seats");

        assertRoute(ShowResource.class.getDeclaredMethod("create", dto.request.ShowRequest.class, jakarta.servlet.http.HttpServletRequest.class), POST.class, "/shows");
        assertRoute(ShowResource.class.getDeclaredMethod("update", Long.class, dto.request.ShowRequest.class, jakarta.servlet.http.HttpServletRequest.class), PUT.class, "/shows/{id}");
        assertRoute(ShowResource.class.getDeclaredMethod("delete", Long.class, jakarta.servlet.http.HttpServletRequest.class), DELETE.class, "/shows/{id}");
    }

    private void assertRoute(Method method, Class<? extends Annotation> httpMethod, String path) {
        assertNotNull(method.getAnnotation(httpMethod));
        Path annotation = method.getAnnotation(Path.class);
        assertNotNull(annotation, "Missing @Path on " + method.getName());
        assertEquals(path, annotation.value());
    }
}
