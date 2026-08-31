package repository;

import config.Database;

import java.sql.Connection;
import java.sql.SQLException;

/*
 * Base repository class providing helper methods to execute database operations within transactions.
 */
abstract class JdbcSupport {

    @FunctionalInterface
    interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    // Executes a database operation using the current connection or transaction
    protected <T> T execute(SqlWork<T> work) {
        Connection connection = Database.current();
        try {
            return work.run(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("Database error", e);
        } finally {
            // Close connection only if it's not managed by a transaction
            Database.closeIfUnmanaged(connection);
        }
    }
}
