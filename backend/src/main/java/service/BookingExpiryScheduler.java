package service;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/*
 * Background scheduler that periodically checks and expires pending bookings.
 */
@Singleton
public class BookingExpiryScheduler {

    private static final Logger LOGGER = Logger.getLogger(BookingExpiryScheduler.class.getName());
    private static final long DEFAULT_INTERVAL_SECONDS = 30;

    private final BookingService bookingService;
    private ScheduledExecutorService executor;

    @Inject
    public BookingExpiryScheduler(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    // Starts the background scheduler task
    public void start() {
        long intervalSeconds = Long.getLong(
            "booking.expiry.interval.seconds",
            DEFAULT_INTERVAL_SECONDS
        );
        if (intervalSeconds <= 0) {
            throw new IllegalArgumentException(
                "booking.expiry.interval.seconds must be positive"
            );
        }

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "booking-expiry");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(() -> {
            try {
                runOnce();
            } catch (SQLException e) {
                LOGGER.log(Level.WARNING,
                    "event=system.error requestId=REQ-unknown location=booking.expiry.scheduler", e);
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        LOGGER.info("event=booking.expiry.scheduler.started requestId=REQ-unknown intervalSeconds="
            + intervalSeconds);
    }

    // Stops the background scheduler
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // Triggers the expiry logic in BookingService
    private void runOnce() throws SQLException {
        long started = System.nanoTime();
        try {
            bookingService.expirePendingBookings();
            LOGGER.info("event=booking.expiry.completed durationMs="
                + ((System.nanoTime() - started) / 1_000_000L));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING,
                "event=system.error requestId=REQ-unknown location=booking.expiry.operation", e);
        }
    }
}
