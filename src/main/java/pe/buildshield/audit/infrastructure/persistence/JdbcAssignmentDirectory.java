package pe.buildshield.audit.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pe.buildshield.audit.domain.model.AssignmentDirectory;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

/** {@code assignment_projection}: asignaciones vigentes copiadas de organization. */
@Repository
class JdbcAssignmentDirectory implements AssignmentDirectory {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    JdbcAssignmentDirectory(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void assign(UUID organizationId, UUID assignmentId, UUID userId, UUID locationId, String locationType) {
        jdbc.update("""
                INSERT INTO assignment_projection (assignment_id, organization_id, user_id, location_id, location_type,
                    updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (assignment_id) DO UPDATE SET user_id = EXCLUDED.user_id,
                    location_id = EXCLUDED.location_id, location_type = EXCLUDED.location_type,
                    updated_at = EXCLUDED.updated_at
                """, assignmentId, organizationId, userId, locationId, locationType, Timestamp.from(clock.instant()));
    }

    @Override
    public void unassign(UUID assignmentId) {
        jdbc.update("DELETE FROM assignment_projection WHERE assignment_id = ?", assignmentId);
    }

    @Override
    public boolean isAssigned(UUID organizationId, UUID userId, UUID locationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM assignment_projection
                               WHERE organization_id = ? AND user_id = ? AND location_id = ?)
                """, Boolean.class, organizationId, userId, locationId));
    }
}
