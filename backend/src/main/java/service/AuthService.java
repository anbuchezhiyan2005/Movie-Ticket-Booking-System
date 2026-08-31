package service;

import dto.response.AuthResponse;
import dto.request.LoginRequest;
import dto.request.RegisterRequest;
import enums.Role;
import exception.ConflictException;
import exception.NotFoundException;
import exception.UnauthorizedException;
import exception.ValidationException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import model.User;
import repository.UserRepository;
import util.PasswordHasher;

@Singleton
public class AuthService {

    public static final int CUSTOMER_STARTING_BALANCE = 10000;
    public static final int ADMIN_STARTING_BALANCE = 0;

    private final UserRepository userRepository;

    @Inject
    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User register(RegisterRequest request) {
        validateRegister(request);

        Role role;
        try {
            role = Role.valueOf(request.getRole().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Role must be CUSTOMER or ADMIN");
        }

        if (role != Role.CUSTOMER) {
            throw new ValidationException("Public registration is only available for customers");
        }

        if (userRepository.findByEmail(request.getEmail().trim()).isPresent()) {
            throw new ConflictException("Email is already registered");
        }

        User user = new User();
        user.setName(request.getName().trim());
        user.setEmail(request.getEmail().trim().toLowerCase());
        user.setPasswordHash(PasswordHasher.hash(request.getPassword()));
        user.setRole(role);
        user.setWalletBalance(role == Role.CUSTOMER ? CUSTOMER_STARTING_BALANCE : ADMIN_STARTING_BALANCE);
        return userRepository.save(user);
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

    public AuthResponse toAuthResponse(User user) {
        AuthResponse response = new AuthResponse();
        response.setUserId(user.getId());
        response.setName(user.getName());
        response.setEmail(user.getEmail());
        response.setRole(user.getRole());
        response.setWalletBalance(user.getWalletBalance());
        return response;
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
}
