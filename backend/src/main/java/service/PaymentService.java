package service;

import exception.NotFoundException;
import exception.ValidationException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import model.User;
import repository.UserRepository;

@Singleton
public class PaymentService {

    private final UserRepository userRepository;

    @Inject
    public PaymentService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public void processPayment(Long customerId, Long adminId, int amount) {
        validateAmount(amount);

        User customer = getUser(customerId);
        User admin = getUser(adminId);

        if (!userRepository.debitWalletBalance(customerId, amount)) {
            throw new ValidationException("Insufficient wallet balance");
        }

        userRepository.creditWalletBalance(adminId, amount);
    }

    public void refundPayment(Long customerId, Long adminId, int refundAmount) {
        if (refundAmount < 0) {
            throw new ValidationException("Refund amount cannot be negative");
        }
        if (refundAmount == 0) {
            return;
        }

        User customer = getUser(customerId);
        User admin = getUser(adminId);

        if (!userRepository.debitWalletBalance(adminId, refundAmount)) {
            throw new ValidationException("Admin wallet has insufficient balance for refund");
        }

        userRepository.creditWalletBalance(customerId, refundAmount);
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    private void validateAmount(int amount) {
        if (amount <= 0) {
            throw new ValidationException("Payment amount must be greater than zero");
        }
    }
}
