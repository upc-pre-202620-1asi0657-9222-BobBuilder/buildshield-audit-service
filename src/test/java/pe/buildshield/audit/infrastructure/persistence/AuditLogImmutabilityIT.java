package pe.buildshield.audit.infrastructure.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import pe.buildshield.audit.domain.model.AuditEntry;
import pe.buildshield.audit.domain.model.AuditLogRepository;
import pe.buildshield.audit.support.AuditIntegrationTest;
import pe.buildshield.audit.support.TestDatabase;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QAS05 y QAS09: el registro solo admite anexar; la base rechaza UPDATE, DELETE y TRUNCATE (SQLSTATE 42501). */
@AuditIntegrationTest
class AuditLogImmutabilityIT {

    @Autowired
    AuditLogRepository auditLog;

    @Autowired
    JdbcTemplate jdbc;

    private UUID eventId;

    @BeforeEach
    void appendOne() {
        TestDatabase.reset(jdbc);
        eventId = UUID.randomUUID();
        Instant now = Instant.now();
        assertThat(auditLog.append(new AuditEntry(UUID.randomUUID(), eventId, "buildshield.ordering",
                "DispatchDeparted", "DISPATCH", "d", UUID.randomUUID(), null, now, now, null, "{\"a\":1}"))).isTrue();
        assertThat(auditLog.append(new AuditEntry(UUID.randomUUID(), eventId, "buildshield.ordering",
                "DispatchDeparted", "DISPATCH", "d", UUID.randomUUID(), null, now, now, null, "{\"a\":2}"))).isFalse();
    }

    @Test
    void update_is_rejected() {
        assertRejected(() -> jdbc.update("UPDATE audit_log SET event_type = 'Changed' WHERE event_id = ?", eventId));
        assertThat(jdbc.queryForObject("SELECT event_type FROM audit_log WHERE event_id = ?", String.class, eventId))
                .isEqualTo("DispatchDeparted");
    }

    @Test
    void delete_is_rejected() {
        assertRejected(() -> jdbc.update("DELETE FROM audit_log WHERE event_id = ?", eventId));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log", Long.class)).isEqualTo(1);
    }

    @Test
    void truncate_is_rejected() {
        assertRejected(() -> jdbc.execute("TRUNCATE TABLE audit_log"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log", Long.class)).isEqualTo(1);
    }

    private static void assertRejected(Runnable mutation) {
        assertThatThrownBy(mutation::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(ex -> {
                    Throwable cause = ex;
                    while (cause != null && !(cause instanceof SQLException)) {
                        cause = cause.getCause();
                    }
                    assertThat(cause).isNotNull();
                    assertThat(((SQLException) cause).getSQLState()).isEqualTo("42501");
                    assertThat(cause.getMessage()).contains("solo admite anexar");
                });
    }
}
