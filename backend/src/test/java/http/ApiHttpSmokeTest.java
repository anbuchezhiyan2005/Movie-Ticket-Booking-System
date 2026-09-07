package http;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ApiHttpSmokeTest {

    private static final String BASE_URL = System.getProperty(
            "api.base.url",
            "http://localhost:8080/movie-booking/api");
    private static HttpTestClient customer;

    @BeforeAll
    static void requireOptIn() {
        assumeTrue(Boolean.getBoolean("run.http.tests"),
                "HTTP tests disabled; run with -Drun.http.tests=true against deployed Tomcat");
        customer = new HttpTestClient(BASE_URL);
    }

    @Test
    void healthAndSeededMoviesAreAvailableThroughTomcat() throws Exception {
        HttpResponse<String> health = customer.get("/health");
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("\"status\":\"ok\""));

        HttpResponse<String> movies = customer.get("/movies");
        assertEquals(200, movies.statusCode());
        assertTrue(movies.body().contains("Inception"));
    }

    @Test
    void customerRegistrationLoginSessionAndLogoutWorkThroughHttp() throws Exception {
        String email = "http-test-" + UUID.randomUUID() + "@example.com";
        String password = "password123";

        HttpResponse<String> registration = customer.post("/auth/register", """
                {"name":"HTTP Test Customer","email":"%s","password":"%s","role":"CUSTOMER"}
                """.formatted(email, password));
        assertEquals(201, registration.statusCode());
        assertTrue(registration.body().contains("\"role\":\"CUSTOMER\""));
        assertTrue(registration.body().contains("\"walletBalance\":10000"));

        HttpResponse<String> login = customer.post("/auth/login", """
                {"email":"%s","password":"%s"}
                """.formatted(email, password));
        assertEquals(200, login.statusCode());

        HttpResponse<String> me = customer.get("/auth/me");
        assertEquals(200, me.statusCode());
        assertTrue(me.body().contains(email));

        HttpResponse<String> logout = customer.post("/auth/logout", "{}");
        assertEquals(204, logout.statusCode());

        HttpResponse<String> protectedRequest = customer.get("/bookings");
        assertEquals(401, protectedRequest.statusCode());
        assertTrue(protectedRequest.body().contains("Not authenticated"));
    }

    @Test
    void otpRoutesRequireAuthentication() throws Exception {
        assertEquals(401, customer.post("/bookings/otp/request", "{}" ).statusCode());
        assertEquals(401, customer.post("/bookings/1/otp/verify",
                "{\"challengeToken\":\"x\",\"code\":\"000000\"}").statusCode());
        assertEquals(401, customer.post("/bookings/1/otp/resend", "{}").statusCode());
        assertEquals(401, customer.post("/bookings/1/cancel/otp/request", "{}").statusCode());
        assertEquals(401, customer.post("/bookings/1/cancel/otp/verify",
                "{\"challengeToken\":\"x\",\"code\":\"000000\"}").statusCode());
        assertEquals(401, customer.post("/bookings/1/cancel/otp/resend", "{}").statusCode());
    }
}