import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Boilerplate for an API-level movie booking simulation.
 *
 * Each simulator thread should own one instance of this class and one HTTP
 * session. Event methods will be implemented after the single-thread workflow
 * is agreed and tested.
 */

public abstract class Simulation {

    public static void main(String[] args) throws Exception {
        int customerCount = 10;
        String baseUrl = "https://localhost:8443/movie-booking/api";
        String movieTitle = "Inception";
        Long showId = 544L;
        int initialWalletBalance = 10000;
        int ticketCost = 250;

        System.out.println("Starting simulation");
        System.out.println("Customers: " + customerCount);
        System.out.println("Base URL: " + baseUrl);
        System.out.println("Movie: " + movieTitle);
        System.out.println("Show ID: " + showId);

        try(SimulationLogger logger = new SimulationLogger("simulation.log")) {
            logger.log("Simulation", "START", "simulation has started");
            List<TestCustomer> customers = createTestCustomers(customerCount);
            List<CustomerSimulation> simulations = new ArrayList<>();

            for (int index = 0; index < customers.size(); index++) {
                TestCustomer customer = customers.get(index);
                CustomerSimulation simulation =
                        new CustomerSimulation(
                            baseUrl, 
                            movieTitle, 
                            showId,
                            initialWalletBalance,
                            ticketCost,
                            customer, 
                            index + 1,
                            logger
                        );
                try {
                    simulation.register(new RegisterInput(
                            customer.name(),
                            customer.email(),
                            customer.password()
                    ));
                    simulations.add(simulation);
                } catch (Exception e) {
                    System.err.println("Failed to register customer: " + customer.email());
                    e.printStackTrace();
                }     
            }

            ExecutorService executor = Executors.newFixedThreadPool(customerCount);
            List<Future<CustomerResult>> results = new ArrayList<>();

            try {
                for (CustomerSimulation simulation : simulations) {
                    results.add(executor.submit(simulation::runWorkflow));
                }

                for (Future<CustomerResult> result : results) {
                    recordResult(result.get());
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                executor.shutdown();
            }
            
            logger.log("Simulation", "END", "simulation has finished");
        }

        System.out.println("Simulation skeleton finished");
    }

    public static List<TestCustomer> createTestCustomers(int count) {
        List<TestCustomer> customers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = "Simulation Customer " + (i + 1);
            String email = "simulation-customer-" + UUID.randomUUID() + "@example.com";
            String password = "SimulationPassword-" + (i + 1);
            customers.add(new TestCustomer(name, email, password));
        }
        return customers;
    }

    public static void recordResult(CustomerResult result) {
        System.out.println(result.customerEmail() + ": " + result.message());
    }

    protected abstract LoginResult login(LoginInput input);

    protected abstract MovieResult findMovie();

    protected abstract ShowResult findShow();

    protected abstract List<SeatResult> getSeatMap();

    protected abstract SeatSelectionResult chooseSeats(List<SeatResult> availableSeats);

    protected abstract BookingOtpResult requestBookingOtp(BookingInput input);

    protected abstract BookingResult verifyBookingOtp(BookingOtpInput input);

    protected abstract CancellationOtpResult requestCancellationOtp(Long bookingId);

    protected abstract CancellationResult verifyCancellationOtp(CancellationOtpInput input);

    protected abstract void logout();

    public record LoginInput(String email, String password) {}

    public record LoginResult(Long userId, String csrfToken) {}

    public record RegisterInput(String name, String email, String password) {}

    public record RegisterResult(Long userId, String email) {}

    public record MovieResult(Long movieId, String title) {}

    public record ShowResult(Long showId, Long movieId, String startTime) {}

    public record SeatResult(String rowLabel, int seatNumber, String status, boolean available) {}

    public record SeatSelectionResult(Long showId, List<SeatResult> seats) {}

    public record BookingInput(List<SeatResult> seats) {}

    public record BookingOtpResult(Long bookingId, String challengeToken, String expiresAt,
                                   String simulationOtp) {}

    public record BookingOtpInput(Long bookingId, String challengeToken, String code) {}

    public record BookingResult(Long bookingId, String status, int totalAmount) {}

    public record CancellationOtpResult(Long bookingId, String challengeToken, String expiresAt,
                                        String simulationOtp) {}

    public record CancellationOtpInput(Long bookingId, String challengeToken, String code) {}

    public record CancellationResult(Long bookingId, String status, int refundAmount) {}

    public record TestCustomer(String name, String email, String password) {}

    public record CustomerResult(String customerEmail, boolean successful, String message) {}

        public record LogEntry(
            java.time.Instant timestamp,
            String customerLabel,
            String action,
            String message
        ) {}
}
