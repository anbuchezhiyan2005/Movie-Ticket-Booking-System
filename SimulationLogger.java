import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class SimulationLogger implements AutoCloseable {
    private final BlockingQueue<Simulation.LogEntry> logQueue = new LinkedBlockingQueue<>(1000);
    private final BufferedWriter logWriter;
    private final Thread loggerThread;
    private static final Simulation.LogEntry STOP_EVENT =
            new Simulation.LogEntry("", "", "", "STOP");

    public SimulationLogger(String logFile) throws IOException {
        logWriter = new BufferedWriter(new FileWriter(logFile, true));
        loggerThread = new Thread(this::processLogQueue, "SimulationLoggerThread");
        loggerThread.start();
    }

    public void log(String timestamp, String threadName, String action, String result) {
        logQueue.offer(new Simulation.LogEntry(timestamp, threadName, action, result));
    }

    private void processLogQueue() {
        try {
            while (true) {
                Simulation.LogEntry entry = logQueue.take();

                if (entry == STOP_EVENT) {
                    break;
                }

                logWriter.write(String.format(
                    "%s [%s] %s: %s", 
                    entry.timestamp(),
                    entry.threadName(),
                    entry.action(),
                    entry.result()
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

    @Override
    public void close() throws InterruptedException {
        logQueue.offer(STOP_EVENT);
        loggerThread.join();
    }
}
