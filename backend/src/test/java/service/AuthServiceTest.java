package service;

import dto.request.RegisterRequest;
import enums.AuthProvider;
import enums.Role;
import exception.ValidationException;
import model.User;
import model.UserIdentity;
import org.junit.jupiter.api.Test;
import repository.UserIdentityRepository;
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
            if (user.getId() == null) {
                user.setId((long) (users.size() + 1));
            }
            users.put(user.getEmail().trim().toLowerCase(), user);
            return user;
        }

        @Override
        public Optional<User> findById(Long userId) {
            return users.values().stream().filter(user -> userId.equals(user.getId())).findFirst();
        }
    }

    private static class FakeUserIdentityRepository extends UserIdentityRepository {
        private final Map<String, UserIdentity> identities = new HashMap<>();

        @Override
        public Optional<UserIdentity> findByProviderSubject(AuthProvider provider, String subject) {
            return Optional.ofNullable(identities.get(provider.name() + ":" + subject));
        }

        @Override
        public UserIdentity save(UserIdentity identity) {
            identity.setId((long) (identities.size() + 1));
            identities.put(identity.getProvider().name() + ":" + identity.getProviderSubject(), identity);
            return identity;
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

    @Test
    void socialProfileCreatesCustomerAndIdentity() {
        FakeUserRepository users = new FakeUserRepository();
        FakeUserIdentityRepository identities = new FakeUserIdentityRepository();
        AuthService authService = new AuthService(users, identities);

        User created = authService.authenticateSocial(
                AuthProvider.GOOGLE,
                new OAuthProviderService.OAuthProfile("google-sub", "customer@example.com", "Customer"));

        assertEquals(Role.CUSTOMER, created.getRole());
        assertEquals(10000, created.getWalletBalance());
        assertTrue(identities.findByProviderSubject(AuthProvider.GOOGLE, "google-sub").isPresent());
    }
}
