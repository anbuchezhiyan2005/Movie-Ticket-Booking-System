package config;

import cache.SeatAvailabilityCache;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.internal.inject.AbstractBinder;

import repository.BookingRepository;
import repository.MovieRepository;
import repository.ScreenRepository;
import repository.ShowRepository;
import repository.ShowSeatRepository;
import repository.TheatreRepository;
import repository.UserRepository;
import repository.BookingGateTokenRepository;

import service.AuthService;
import service.BookingExpiryScheduler;
import service.BookingService;
import service.ConfirmationEmailService;
import service.GateTokenService;
import service.GateValidationService;
import service.MovieService;
import service.PaymentService;
import service.ScreenService;
import service.ShowService;
import service.SmtpConfirmationEmailService;
import service.TheatreService;

/*
 * The JerseyApplication class configures the Jersey framework for the application.
 * It registers resource packages, JSON support, and binds various repositories and 
 * services for dependency injection,enabling RESTful API functionality.
 */


public class JerseyApplication extends ResourceConfig {
    private SeatAvailabilityCache seatAvailabilityCache;

    public JerseyApplication() {
        packages("resource", "exception");
        register(JacksonFeature.class);

        // Create and start cache cleanup scheduler
        seatAvailabilityCache = new SeatAvailabilityCache();
        seatAvailabilityCache.startCleanupScheduler();

        register(new AbstractBinder() {
            @Override
            protected void configure() {
                bindAsContract(UserRepository.class);
                bindAsContract(MovieRepository.class);
                bindAsContract(TheatreRepository.class);
                bindAsContract(ScreenRepository.class);
                bindAsContract(ShowRepository.class);
                bindAsContract(ShowSeatRepository.class);
                bindAsContract(BookingRepository.class);
                bindAsContract(BookingGateTokenRepository.class);

                bindAsContract(AuthService.class);
                bindAsContract(MovieService.class);
                bindAsContract(TheatreService.class);
                bindAsContract(ScreenService.class);
                bindAsContract(ShowService.class);
                bindAsContract(BookingService.class);
                bindAsContract(GateTokenService.class);
                bindAsContract(GateValidationService.class);
                bindAsContract(PaymentService.class);
                bindAsContract(SmtpConfirmationEmailService.class).to(ConfirmationEmailService.class);
                bindAsContract(BookingExpiryScheduler.class);

                // Register cache singleton
                bind(seatAvailabilityCache).to(SeatAvailabilityCache.class);
            }
        });

        register(RuntimeLifeCycle.class);
    }
}
