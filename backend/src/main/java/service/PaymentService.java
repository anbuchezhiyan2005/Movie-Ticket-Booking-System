package service;

import exception.ValidationException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import repository.UserRepository;
import util.RequestLogContext;

import java.util.logging.Logger;

@Singleton
public class PaymentService {

    private static final Logger LOGGER = Logger.getLogger(PaymentService.class.getName());

    private final UserRepository userRepository;

    @Inject
    public PaymentService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public void processPayment(Long customerId, Long adminId, int amount) {
        if (amount <= 0) {
            throw new ValidationException("Payment amount must be greater than zero");
        }

        if (!userRepository.debitWalletBalance(customerId, amount)) {
            throw new ValidationException("Insufficient wallet balance");
        }

        userRepository.creditWalletBalance(adminId, amount);
        LOGGER.info("event=payment.charged requestId=" + RequestLogContext.requestId()
            + " customerId=" + customerId + " adminId=" + adminId + " amount=" + amount);
    }

    public void refundPayment(Long customerId, Long adminId, int refundAmount) {
        if (refundAmount < 0) {
            throw new ValidationException("Refund amount cannot be negative");
        }
        if (refundAmount == 0) {
            return;
        }

        if (!userRepository.debitWalletBalance(adminId, refundAmount)) {
            throw new ValidationException("Admin wallet has insufficient balance for refund");
        }

        userRepository.creditWalletBalance(customerId, refundAmount);
        LOGGER.info("event=payment.refunded requestId=" + RequestLogContext.requestId()
            + " customerId=" + customerId + " adminId=" + adminId + " amount=" + refundAmount);
    }

}
