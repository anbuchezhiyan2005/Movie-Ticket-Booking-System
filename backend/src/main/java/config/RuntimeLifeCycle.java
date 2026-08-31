package config;

import jakarta.annotation.PostConstruct; // Import the PostConstruct annotation for lifecycle management
import jakarta.annotation.PreDestroy; // Import the PreDestroy annotation for lifecycle management
import jakarta.inject.Inject; // Import the Inject annotation for dependency injection
import jakarta.inject.Singleton; // Import the Singleton annotation to ensure a single instance of the class
import service.BookingExpiryScheduler; // Import the BookingExpiryScheduler service to manage booking expiry tasks

// The RuntimeLifeCycle class manages the lifecycle of the BookingExpiryScheduler service.
@Singleton
public class RuntimeLifeCycle {

    private final BookingExpiryScheduler bookingExpiryScheduler;

    // Constructor that injects the BookingExpiryScheduler dependency
    @Inject
    public RuntimeLifeCycle(BookingExpiryScheduler bookingExpiryScheduler) {
        this.bookingExpiryScheduler = bookingExpiryScheduler;
    }

    // The start method is called after the bean's construction to start the booking expiry scheduler
    @PostConstruct
    public void start() {
        bookingExpiryScheduler.start();
    }

    // The stop method is called before the bean's destruction to stop the booking expiry scheduler
    @PreDestroy
    public void stop() {
        bookingExpiryScheduler.stop();
    }
}
