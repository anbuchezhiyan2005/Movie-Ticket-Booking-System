package service;

import java.time.LocalDateTime;
import java.util.List;

public record ConfirmationEmail(
        String recipient,
        String customerName,
        Long bookingId,
        String movieName,
        String theatreName,
        String theatreLocation,
        LocalDateTime showTime,
        List<String> seats,
        int totalAmount) {
}
