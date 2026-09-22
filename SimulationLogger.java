import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class SimulationLogger implements AutoCloseable {
    private final BlockingQueue<Simulation.LogEntry> logQueue = new LinkedBlockingQueue<>(1000);
    private final BufferedWriter logWriter;
    private final Thread loggerThread;
    private static final DateTimeFormatter IST_FORMATTER =
            DateTimeFormatter.ofPattern("h:mm a dd-MM-yyyy")
                .withZone(ZoneId.of("Asia/Kolkata"));
    private static final Simulation.LogEntry STOP_EVENT =
            new Simulation.LogEntry(Instant.EPOCH, "", "", "STOP");

    public SimulationLogger(String logFile) throws IOException {
        logWriter = new BufferedWriter(new FileWriter(logFile, true));
        loggerThread = new Thread(this::processLogQueue, "SimulationLoggerThread");
        loggerThread.start();
    }

    public void log(String customerLabel, String action, String message) {
        logQueue.offer(new Simulation.LogEntry(
                Instant.now(), customerLabel, action, message
        ));
    }

    private void processLogQueue() {
        try {
            while (true) {
                Simulation.LogEntry entry = logQueue.take();

                if (entry == STOP_EVENT) {
                    break;
                }

                logWriter.write(String.format(
                    "[%s] %s | %s | %s",
                    IST_FORMATTER.format(entry.timestamp()),
                    entry.customerLabel(),
                    entry.action(),
                    readableMessage(entry.action(), entry.message())
                ));
                logWriter.newLine();
                logWriter.flush();
            } 
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                logWriter.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private String readableMessage(String action, String message) {
        if ("SEAT_CONFLICT".equals(action)
                || "BOOKING_RETRY".equals(action)) {
            return "selected seats were unavailable; refreshing the seat map"
                    + details(message);
        }
        if ("CANCELLATION_COMPLETE".equals(action)) {
            return "has cancelled the booking and received the refund"
                    + details(message);
        }
        if (message != null && message.startsWith("SUCCESS ")) {
            return "has completed this action successfully"
                    + details(message.substring("SUCCESS ".length()));
        }
        if (message != null && message.startsWith("FAILED ")) {
            return "could not complete this action"
                    + details(message.substring("FAILED ".length()));
        }
        return message == null ? "" : message;
    }

    private String details(String message) {
        return message == null || message.isBlank() ? "" : " (" + message + ")";
    }

    @Override
    public void close() throws InterruptedException {
        logQueue.offer(STOP_EVENT);
        loggerThread.join();
    }
}
