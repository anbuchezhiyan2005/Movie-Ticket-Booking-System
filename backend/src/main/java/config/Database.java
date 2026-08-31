package config;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/*
 * Manages database configuration, connection pooling/opening, and transaction boundaries using ThreadLocal.
 */

public final class Database {

    // ThreadLocal to hold active transaction connection per thread
    private static final ThreadLocal<Connection> TX = new ThreadLocal<>();
    private static String url;
    private static String user;
    private static String password;

    private Database() {
    }

    // Initializes database properties and loads the MySQL JDBC driver
    public static void init() {
        Properties properties = new Properties();
        try (InputStream in = Database.class.getClassLoader().getResourceAsStream("db.properties")) {
            if (in == null) {
                throw new IllegalStateException("db.properties not found on classpath");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load db.properties", e);
        }

        url = properties.getProperty("db.url");
        user = properties.getProperty("db.user");
        password = properties.getProperty("db.password");

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("MySQL driver not found", e);
        }
    }

    // Opens a new database connection
    public static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    // Returns the current active connection (transaction connection if in transaction, otherwise opens a new one)
    public static Connection current() {
        Connection tx = TX.get();
        if (tx != null) {
            return tx;
        }
        try {
            return openConnection();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not open database connection", e);
        }
    }

    // Checks if the current thread is inside a transaction
    public static boolean inTransaction() {
        return TX.get() != null;
    }

    // Closes the connection only if it is not managed by an active transaction
    public static void closeIfUnmanaged(Connection connection) {
        if (TX.get() != null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not close connection", e);
        }
    }

    // Executes a block of code within a database transaction (commits on success, rolls back on exception)
    public static <T> T inTransaction(Work<T> work) throws SQLException {
        if (TX.get() != null) {
            return work.run();
        }

        Connection connection;
        try {
            connection = openConnection();
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not start transaction", e);
        }

        TX.set(connection);
        try {
            T result = work.run();
            connection.commit();
            return result;
        } catch (RuntimeException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                e.addSuppressed(rollbackError);
            }
            throw e;
        } finally {
            TX.remove();
            try {
                connection.close();
            } catch (SQLException e) {
                throw new IllegalStateException("Could not close transaction connection", e);
            }
        }
    }

    @FunctionalInterface
    public interface Work<T> {
        T run();
    }
}
