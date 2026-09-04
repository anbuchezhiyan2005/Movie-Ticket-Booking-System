package service;

import dto.request.RegisterRequest;
import enums.Role;
import exception.ValidationException;
import model.User;
import org.junit.jupiter.api.Test;
import repository.UserRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {

    private static class FakeUserRepository extends UserRepository {
        private final Map<String, User> users = new HashMap<>();

        @Override
        public Optional<User> findByEmail(String email) {
            return Optional.ofNullable(users.get(email.trim().toLowerCase()));
        }

        @Override
        public User save(User user) {
            users.put(user.getEmail().trim().toLowerCase(), user);
            return user;
        }
    }

    @Test
    void registerAdminRequiresValidAccessCode() {
        AuthService authService = new AuthService(new FakeUserRepository());
        RegisterRequest request = new RegisterRequest();
        request.setName("Admin User");
        request.setEmail("admin@example.com");
        request.setPassword("admin123");
        request.setRole("ADMIN");
        request.setAdminKey("wrong-key");

        assertThrows(ValidationException.class, () -> authService.registerAdmin(request));
    }

    @Test
    void registerAdminAcceptsValidAccessCodeAndCreatesAdminAccount() {
        System.setProperty("movie.booking.admin.secret", "valid-admin-key");
        try {
            AuthService authService = new AuthService(new FakeUserRepository());
            RegisterRequest request = new RegisterRequest();
            request.setName("Admin User");
            request.setEmail("admin@example.com");
            request.setPassword("admin123");
            request.setRole("ADMIN");
            request.setAdminKey("valid-admin-key");

            User saved = authService.registerAdmin(request);

            assertEquals(Role.ADMIN, saved.getRole());
            assertEquals(0, saved.getWalletBalance());
        } finally {
            System.clearProperty("movie.booking.admin.secret");
        }
    }
}
