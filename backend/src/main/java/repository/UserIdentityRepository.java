package repository;

import enums.AuthProvider;
import jakarta.inject.Singleton;
import model.UserIdentity;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

@Singleton
public class UserIdentityRepository extends JdbcSupport {

    public Optional<UserIdentity> findByProviderSubject(AuthProvider provider, String providerSubject) {
        return execute(connection -> {
            String sql = """
                    SELECT id, user_id, provider, provider_subject, provider_email,
                           display_name, created_at, updated_at
                    FROM user_identities
                    WHERE provider = ? AND provider_subject = ?
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, provider.name());
                statement.setString(2, providerSubject);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
                }
            }
        });
    }

    public UserIdentity save(UserIdentity identity) {
        return execute(connection -> {
            String sql = """
                    INSERT INTO user_identities
                        (user_id, provider, provider_subject, provider_email, display_name)
                    VALUES (?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, identity.getUserId());
                statement.setString(2, identity.getProvider().name());
                statement.setString(3, identity.getProviderSubject());
                statement.setString(4, identity.getProviderEmail());
                statement.setString(5, identity.getDisplayName());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        identity.setId(keys.getLong(1));
                    }
                }
                return identity;
            }
        });
    }

    private UserIdentity map(ResultSet resultSet) throws SQLException {
        UserIdentity identity = new UserIdentity();
        identity.setId(resultSet.getLong("id"));
        identity.setUserId(resultSet.getLong("user_id"));
        identity.setProvider(AuthProvider.valueOf(resultSet.getString("provider")));
        identity.setProviderSubject(resultSet.getString("provider_subject"));
        identity.setProviderEmail(resultSet.getString("provider_email"));
        identity.setDisplayName(resultSet.getString("display_name"));
        identity.setCreatedAt(resultSet.getTimestamp("created_at").toLocalDateTime());
        identity.setUpdatedAt(resultSet.getTimestamp("updated_at").toLocalDateTime());
        return identity;
    }
}