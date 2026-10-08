package pe.buildshield.audit.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pe.buildshield.audit.domain.model.UserDirectory;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** {@code user_projection}: copia de los usuarios de iam. */
@Repository
class JdbcUserDirectory implements UserDirectory {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    JdbcUserDirectory(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void upsert(UUID organizationId, UUID userId, String fullName, String email, String role, boolean active) {
        jdbc.update("""
                INSERT INTO user_projection (user_id, organization_id, full_name, email, role, active, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    full_name = COALESCE(EXCLUDED.full_name, user_projection.full_name),
                    email = COALESCE(EXCLUDED.email, user_projection.email),
                    role = EXCLUDED.role, active = EXCLUDED.active, updated_at = EXCLUDED.updated_at
                """, userId, organizationId, fullName, email, role, active, Timestamp.from(clock.instant()));
    }

    @Override
    public Map<UUID, String> names(UUID organizationId, Collection<UUID> userIds) {
        Map<UUID, String> names = new HashMap<>();
        if (userIds.isEmpty()) {
            return names;
        }
        jdbc.query("SELECT user_id, full_name FROM user_projection WHERE organization_id = ? AND user_id = ANY (?)",
                rs -> {
                    names.put(rs.getObject("user_id", UUID.class), rs.getString("full_name"));
                }, organizationId, userIds.toArray(new UUID[0]));
        return names;
    }
}
