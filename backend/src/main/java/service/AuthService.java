package service;

import config.Database;
import dto.response.AuthResponse;
import dto.request.LoginRequest;
import dto.request.RegisterRequest;
import enums.AuthProvider;
import enums.Role;
import exception.ConflictException;
import exception.NotFoundException;
import exception.UnauthorizedException;
import exception.ValidationException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import model.User;
import model.UserIdentity;
import repository.UserIdentityRepository;
import repository.UserRepository;
import util.PasswordHasher;

import java.sql.SQLException;

@Singleton
public class AuthService {

    public static final int CUSTOMER_STARTING_BALANCE = 10000;
    public static final int ADMIN_STARTING_BALANCE = 0;

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;

    @Inject
    public AuthService(UserRepository userRepository, UserIdentityRepository userIdentityRepository) {
        this.userRepository = userRepository;
        this.userIdentityRepository = userIdentityRepository;
    }

    public AuthService(UserRepository userRepository) {
        this(userRepository, null);
    }

    public User register(RegisterRequest request) {
        request.setRole("CUSTOMER");
        validateRegister(request);
        return registerUser(request, Role.CUSTOMER);
    }

    public User registerAdmin(RegisterRequest request) {
        request.setRole("ADMIN");
        validateRegister(request);
        if (isBlank(request.getAdminKey())) {
            throw new ValidationException("Admin access code is required");
        }

        String configuredSecret = System.getProperty("movie.booking.admin.secret", "REELRESERVE-ADMIN-KEY");
        if (!configuredSecret.equals(request.getAdminKey().trim())) {
            throw new ValidationException("Invalid admin access code");
        }

        return registerUser(request, Role.ADMIN);
    }

    public User login(LoginRequest request) {
        if (request == null || isBlank(request.getEmail()) || isBlank(request.getPassword())) {
            throw new ValidationException("Email and password are required");
        }

        User user = userRepository.findByEmail(request.getEmail().trim().toLowerCase())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (!PasswordHasher.matches(request.getPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }
        return user;
    }

    public User getById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    public User authenticateSocial(AuthProvider provider, service.OAuthProviderService.OAuthProfile profile) {
        if (userIdentityRepository == null) {
            throw new IllegalStateException("OAuth identity repository is not configured");
        }
        if (provider == null || profile == null || isBlank(profile.subject())) {
            throw new ValidationException("OAuth profile is incomplete");
        }

        var existingIdentity = userIdentityRepository.findByProviderSubject(provider, profile.subject());
        if (existingIdentity.isPresent()) {
            User user = getById(existingIdentity.get().getUserId());
            if (user.getRole() != Role.CUSTOMER) {
                throw new UnauthorizedException("Only customer accounts can use social sign-in");
            }
            return user;
        }

        try {
            return Database.inTransaction(() -> {
                var transactionIdentity = userIdentityRepository.findByProviderSubject(provider, profile.subject());
                if (transactionIdentity.isPresent()) {
                    User user = getById(transactionIdentity.get().getUserId());
                    if (user.getRole() != Role.CUSTOMER) {
                        throw new UnauthorizedException("Only customer accounts can use social sign-in");
                    }
                    return user;
                }

                String email = normalizedSocialEmail(provider, profile);
                if (email != null && userRepository.findByEmail(email).isPresent()) {
                    throw new ConflictException("An account already uses this email; log in and link the provider");
                }

                User user = new User();
                user.setName(firstNonBlank(profile.displayName(), "Customer"));
                user.setEmail(email == null ? provider.name().toLowerCase() + "_" + profile.subject() + "@users.invalid" : email);
                user.setPhoneNumber(null);
                user.setPasswordHash(null);
                user.setRole(Role.CUSTOMER);
                user.setWalletBalance(CUSTOMER_STARTING_BALANCE);
                user = userRepository.save(user);

                UserIdentity identity = new UserIdentity();
                identity.setUserId(user.getId());
                identity.setProvider(provider);
                identity.setProviderSubject(profile.subject());
                identity.setProviderEmail(profile.email());
                identity.setDisplayName(profile.displayName());
                userIdentityRepository.save(identity);
                return user;
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create social customer", exception);
        } catch (RuntimeException exception) {
            var winningIdentity = userIdentityRepository.findByProviderSubject(provider, profile.subject());
            if (winningIdentity.isPresent()) {
                User user = getById(winningIdentity.get().getUserId());
                if (user.getRole() == Role.CUSTOMER) {
                    return user;
                }
            }
            throw exception;
        }
    }

    public User linkSocial(Long userId, AuthProvider provider,
                           service.OAuthProviderService.OAuthProfile profile) {
        if (userIdentityRepository == null || userId == null || provider == null
                || profile == null || isBlank(profile.subject())) {
            throw new ValidationException("OAuth link details are incomplete");
        }

        User currentUser = getById(userId);
        if (currentUser.getRole() != Role.CUSTOMER) {
            throw new UnauthorizedException("Only customer accounts can link social sign-in");
        }

        try {
            return Database.inTransaction(() -> {
                var existing = userIdentityRepository.findByProviderSubject(provider, profile.subject());
                if (existing.isPresent()) {
                    if (!userId.equals(existing.get().getUserId())) {
                        throw new ConflictException("This provider account is already linked");
                    }
                    return currentUser;
                }

                String email = normalizedSocialEmail(provider, profile);
                if (email != null && !email.equalsIgnoreCase(currentUser.getEmail())
                        && userRepository.findByEmail(email).isPresent()) {
                    throw new ConflictException("This provider email belongs to another account");
                }

                UserIdentity identity = new UserIdentity();
                identity.setUserId(userId);
                identity.setProvider(provider);
                identity.setProviderSubject(profile.subject());
                identity.setProviderEmail(profile.email());
                identity.setDisplayName(profile.displayName());
                userIdentityRepository.save(identity);
                return currentUser;
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not link social account", exception);
        }
    }

    public AuthResponse toAuthResponse(User user) {
        AuthResponse response = new AuthResponse();
        response.setUserId(user.getId());
        response.setName(user.getName());
        response.setEmail(user.getEmail());
        response.setPhoneNumber(user.getPhoneNumber());
        response.setRole(user.getRole());
        response.setWalletBalance(user.getWalletBalance());
        return response;
    }

    private User registerUser(RegisterRequest request, Role role) {
        try {
            Role parsedRole = Role.valueOf(request.getRole().trim().toUpperCase());
            if (parsedRole != role) {
                throw new ValidationException("Role mismatch");
            }
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Role must be CUSTOMER or ADMIN");
        }

        if (userRepository.findByEmail(request.getEmail().trim()).isPresent()) {
            throw new ConflictException("Email is already registered");
        }

        User user = new User();
        user.setName(request.getName().trim());
        user.setEmail(request.getEmail().trim().toLowerCase());
        user.setPhoneNumber(request.getPhoneNumber() == null || request.getPhoneNumber().isBlank()
            ? null : request.getPhoneNumber().trim());
        user.setPasswordHash(PasswordHasher.hash(request.getPassword()));
        user.setRole(role);
        user.setWalletBalance(role == Role.CUSTOMER ? CUSTOMER_STARTING_BALANCE : ADMIN_STARTING_BALANCE);
        return userRepository.save(user);
    }

    private void validateRegister(RegisterRequest request) {
        if (request == null) {
            throw new ValidationException("Register request cannot be null");
        }
        if (isBlank(request.getName())) {
            throw new ValidationException("Name cannot be empty");
        }
        if (isBlank(request.getEmail())) {
            throw new ValidationException("Email cannot be empty");
        }
        if (!isBlank(request.getPhoneNumber()) && !request.getPhoneNumber().trim().matches("\\+[1-9]\\d{7,14}")) {
            throw new ValidationException("Phone number must use E.164 format, for example +14155552671");
        }
        if (isBlank(request.getPassword()) || request.getPassword().length() < 6) {
            throw new ValidationException("Password must be at least 6 characters");
        }
        if (isBlank(request.getRole())) {
            throw new ValidationException("Role must be CUSTOMER or ADMIN");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalizedSocialEmail(AuthProvider provider, service.OAuthProviderService.OAuthProfile profile) {
        if (isBlank(profile.email())) {
            return null;
        }
        return profile.email().trim().toLowerCase();
    }

    private String firstNonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }
}
