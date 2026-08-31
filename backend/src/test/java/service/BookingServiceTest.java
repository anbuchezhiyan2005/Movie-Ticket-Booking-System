package service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BookingServiceTest {

    @Test
    void refundsFullAmountMoreThanThirtyMinutesBeforeShow() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 28, 20, 0);
        LocalDateTime now = start.minusMinutes(31);

        assertEquals(1000, BookingService.refundAmount(1000, start, now));
    }

    @Test
    void refundsSeventyFivePercentWithinThirtyMinutesBeforeShow() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 28, 20, 0);
        LocalDateTime now = start.minusMinutes(30);

        assertEquals(750, BookingService.refundAmount(1000, start, now));
    }

    @Test
    void refundsIntegerAmountForOddTotal() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 28, 20, 0);
        LocalDateTime now = start.minusMinutes(10);

        assertEquals(751, BookingService.refundAmount(1001, start, now));
    }
}