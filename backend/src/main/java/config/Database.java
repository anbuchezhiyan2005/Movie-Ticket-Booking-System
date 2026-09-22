package config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

/*
 * Manages database configuration, a shared connection pool, and transaction boundaries using ThreadLocal.
 */

public final class Database {

    private static final int DEADLOCK_RETRY_LIMIT = 5;
    private static final long DEADLOCK_RETRY_BASE_DELAY_MILLIS = 50L;
    private static final long DEADLOCK_RETRY_MAX_DELAY_MILLIS = 1_000L;
    private static final long DEADLOCK_RETRY_JITTER_MILLIS = 50L;

    private static final ThreadLocal<Connection> TX = new ThreadLocal<>();
    private static HikariDataSource dataSource;
    private static String url;
    private static String user;
    private static String password;

    private Database() {
    }

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

        url = firstConfigured("DB_URL", "db.url", properties.getProperty("db.url"));
        user = firstConfigured("DB_USER", "db.user", properties.getProperty("db.user"));
        password = firstConfigured("DB_PASSWORD", "db.password", properties.getProperty("db.password"));

        if (!url.contains("useUnicode=true") && !url.contains("characterEncoding=")) {
            url = url + (url.contains("?") ? "&" : "?")
                    + "useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_unicode_ci";
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setPoolName("movie-booking-pool");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30_000);
        config.setIdleTimeout(60_000);
        config.setMaxLifetime(30 * 60_000L);
        config.setValidationTimeout(5_000);
        config.setConnectionTestQuery("SELECT 1");
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("useUnicode", "true");
        config.addDataSourceProperty("characterEncoding", "UTF-8");

        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        dataSource = new HikariDataSource(config);
    }

    public static void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private static String firstConfigured(String environmentName, String systemPropertyName, String fileValue) {
        String configuredValue = EnvironmentConfig.get(environmentName);
        if (configuredValue != null) {
            return configuredValue;
        }
        return EnvironmentConfig.get(systemPropertyName, fileValue);
    }

    public static Connection openConnection() throws SQLException {
        if (dataSource == null) {
            throw new IllegalStateException("Database has not been initialized");
        }
        Connection connection = dataSource.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("SET time_zone = '+00:00'");
        }
        return connection;
    }

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

    public static boolean inTransaction() {
        return TX.get() != null;
    }

    public static void closeIfUnmanaged(Connection connection) {
        if (TX.get() != null || connection == null) {
            return;
        }
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not close connection", e);
        }
    }

    public static <T> T inTransaction(Work<T> work) throws SQLException {
        Connection existing = TX.get();
        if (existing != null) {
            return work.run();
        }

        Connection connection = openConnection();
        connection.setAutoCommit(false);
        TX.set(connection);

        try {
            T result = work.run();
            connection.commit();
            return result;
        } catch (RuntimeException e) {
            rollbackQuietly(connection, e);
            throw e;
        } catch (SQLException e) {
            rollbackQuietly(connection, e);
            throw e;
        } finally {
            TX.remove();
            closeIfUnmanaged(connection);
        }
    }

    public static <T> T inTransactionWithDeadlockRetry(Work<T> work) throws SQLException {
        for (int attempt = 1; attempt <= DEADLOCK_RETRY_LIMIT; attempt++) {
            try {
                return inTransaction(work);
            } catch (RuntimeException error) {
                if (!isDeadlock(error) || attempt == DEADLOCK_RETRY_LIMIT) {
                    throw error;
                }
                sleepBeforeRetry(attempt);
            } catch (SQLException error) {
                if (!isDeadlock(error) || attempt == DEADLOCK_RETRY_LIMIT) {
                    throw error;
                }
                sleepBeforeRetry(attempt);
            }
        }
        throw new IllegalStateException("Deadlock retry limit exceeded");
    }

    private static void rollbackQuietly(Connection connection, Throwable error) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            error.addSuppressed(rollbackError);
        }
    }

    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(deadlockRetryDelayMillis(attempt));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for deadlock retry", interrupted);
        }
    }

    private static long deadlockRetryDelayMillis(int attempt) {
        long exponentialDelay = DEADLOCK_RETRY_BASE_DELAY_MILLIS
                * (1L << Math.min(attempt - 1, 10));
        long boundedDelay = Math.min(exponentialDelay, DEADLOCK_RETRY_MAX_DELAY_MILLIS);
        long jitter = ThreadLocalRandom.current().nextLong(DEADLOCK_RETRY_JITTER_MILLIS + 1);
        return Math.min(boundedDelay + jitter, DEADLOCK_RETRY_MAX_DELAY_MILLIS);
    }

    private static boolean isDeadlock(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                int code = sqlException.getErrorCode();
                String sqlState = sqlException.getSQLState();
                if (code == 1213 || "40001".equals(sqlState) || "40P01".equals(sqlState)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    @FunctionalInterface
    public interface Work<T> {
        T run();
    }
}


// package config;

// import com.zaxxer.hikari.HikariDataSource;
// import com.zaxxer.hikari.HikariConfig;
// import java.util.Properties;
// import java.io.InputStream;
// import java.io.IOException;
// import java.sql.Connection;

// public final class Database {
//     private static HikariDataSource dataSource;
//     private static final ThreadLocal<Connection> TX = new ThreadLocal<>();
//     private Database() {}
    
//     public static void init() {
//         HikariConfig config = new HikariConfig();
//         Properties properties = new Properties();
//         try (InputStream input = Database.class.getClassLoader().getResourceAsStream("db.properties")) {
//             if (input == null) {
//                 throw new RuntimeException("Unable to find db.properties");
//             }
//             properties.load(input);
            

//         } catch (IOException e) {
//             throw new RuntimeException("Failed to load database properties", e);
//         }

//         String url = properties.getProperty("db.url");
//         String user = properties.getProperty("db.user");
//         String password = properties.getProperty("db.password");

//         config.setJdbcUrl(url);
//         config.setUsername(user);
//         config.setPassword(password);

//         config.setMaximumPoolSize(10);
//         config.setMinimumIdle(2);

//         dataSource = new HikariDataSource(config);
//     }

//     public static Connection openConnection() throws SQLException {
//         if (dataSource == null) {
//             throw new IllegalStateException("DataSource has not been initialized");
//         }
//         return dataSource.getConnection();
//     }

//     public static Connection current() {
//         Connection tx = TX.get();
//         if (tx != null) {
//             return tx;
//         }
//         try {
//             return openConnection();
//         } catch (SQLException e) {
//             throw new IllegalStateException("Could not open database connection", e);
//         }
//     }

//     public static <T> T inTransaction(Work<T> work) throws SQLException {
//         Connection existing = TX.get();
//         if (existing != null) {
//             return work.run();
//         }

//         Connection connection = openConnection();
//         connection.setAutoCommit(false);
//         TX.set(connection);

//         try {
//             T result = work.run();
//             connection.commit();
//             return result;
//         } catch (Exception e) {
//             connection.rollback();
//             throw new SQLException("Error occurred in transaction", e);
//         } finally {
//             TX.remove();
//             connection.close();
//         }
//     }

    
// }
 