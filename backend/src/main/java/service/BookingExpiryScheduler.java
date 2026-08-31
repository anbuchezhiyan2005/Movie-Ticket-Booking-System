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
    private static final long INTERVAL_SECONDS = 30;

    private final BookingService bookingService;
    private ScheduledExecutorService executor;

    @Inject
    public BookingExpiryScheduler(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    // Starts the background scheduler task
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "booking-expiry");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(() -> {
            try {
                runOnce();
            } catch (SQLException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }, INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    // Stops the background scheduler
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // Triggers the expiry logic in BookingService
    private void runOnce() throws SQLException {
        try {
            bookingService.expirePendingBookings();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Pending booking expiry failed (is MySQL running?)", e);
        }
    }
}
