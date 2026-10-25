package pe.buildshield.audit.infrastructure.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.buildshield.audit.domain.model.AuditEntry;
import pe.buildshield.audit.domain.model.AuditLogRepository;
import pe.buildshield.audit.domain.model.ChainVerification;
import pe.buildshield.audit.domain.model.NewAuditEntry;
import pe.buildshield.audit.support.AuditIntegrationTest;
import pe.buildshield.audit.support.TestDatabase;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QAS05, QAS09 y RNF15 sobre PostgreSQL real: la bitácora solo admite anexar (UPDATE, DELETE y TRUNCATE los rechaza un
 * trigger con SQLSTATE 42501 y el rol del servicio solo tiene INSERT y SELECT), cada evento entra una sola vez y la
 * cadena de huellas SHA-256 de cada organización se puede verificar y delata cualquier alteración.
 */
@AuditIntegrationTest
class AuditLogImmutabilityIT {

    @Autowired
    AuditLogRepository auditLog;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private TransactionTemplate tx;
    private UUID organization;
    private UUID eventId;

    @BeforeEach
    void appendOne() {
        TestDatabase.reset(jdbc);
        tx = new TransactionTemplate(transactions);
        organization = UUID.randomUUID();
        eventId = UUID.randomUUID();
        assertThat(append(organization, eventId, "{\"a\":1}")).isPresent();
        assertThat(append(organization, eventId, "{\"a\":2}")).as("el mismo evento no entra dos veces").isEmpty();
    }

    @Test
    void update_is_rejected() {
        assertRejected(() -> jdbc.update("UPDATE audit_log SET action = 'Changed' WHERE event_id = ?", eventId));
        assertThat(jdbc.queryForObject("SELECT action FROM audit_log WHERE event_id = ?", String.class, eventId))
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

    @Test
    void the_service_role_only_has_insert_and_select() {
        for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")) {
            assertThat(hasPrivilege(privilege)).as(privilege).isFalse();
        }
        assertThat(hasPrivilege("INSERT")).isTrue();
        assertThat(hasPrivilege("SELECT")).isTrue();
    }

    @Test
    void entries_are_chained_per_organization_and_the_chain_verifies() {
        UUID other = UUID.randomUUID();
        append(organization, UUID.randomUUID(), "{\"b\":2}");
        append(other, UUID.randomUUID(), "{\"c\":3}");
        append(organization, UUID.randomUUID(), "{\"d\": {\"z\": 1, \"a\": [1, 2]}}");

        List<AuditEntry> chain = chain(organization);

        assertThat(chain).hasSize(3);
        assertThat(chain.get(0).prevHash()).isEqualTo(AuditEntry.GENESIS_HASH);
        assertThat(chain.get(1).prevHash()).isEqualTo(chain.get(0).hash());
        assertThat(chain.get(2).prevHash()).isEqualTo(chain.get(1).hash());
        assertThat(chain(other).get(0).prevHash()).isEqualTo(AuditEntry.GENESIS_HASH);
        assertThat(chain).allSatisfy(entry -> assertThat(entry.hashMatches()).isTrue());
        assertThat(verify(organization)).isEqualTo(new ChainVerification(true, 3, null, null));
        assertThat(verify(other).valid()).isTrue();
        assertThat(verify(UUID.randomUUID())).isEqualTo(new ChainVerification(true, 0, null, null));
    }

    @Test
    void concurrent_appends_of_one_organization_keep_a_single_unbroken_chain() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<CompletableFuture<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int n = i;
                tasks.add(CompletableFuture.runAsync(() -> append(organization, UUID.randomUUID(), "{\"n\":" + n + "}"),
                        pool));
            }
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get();
        } finally {
            pool.shutdown();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE organization_id = ?", Long.class,
                organization)).isEqualTo(41);
        assertThat(verify(organization)).isEqualTo(new ChainVerification(true, 41, null, null));
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT prev_hash) FROM audit_log WHERE organization_id = ?",
                Long.class, organization)).isEqualTo(41);
    }

    @Test
    void an_altered_entry_is_found_by_the_verification() {
        append(organization, UUID.randomUUID(), "{\"n\":2}");
        append(organization, UUID.randomUUID(), "{\"n\":3}");
        AuditEntry second = chain(organization).get(1);

        // Alguien con permisos de superusuario y el trigger apagado en su sesión cambia una entrada.
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET session_replication_role = replica");
                statement.execute("UPDATE audit_log SET after_value = '{\"n\":999}' WHERE id = " + second.id());
                statement.execute("SET session_replication_role = DEFAULT");
            }
            return null;
        });

        ChainVerification result = verify(organization);
        assertThat(result.valid()).isFalse();
        assertThat(result.brokenEntryId()).isEqualTo(second.id());
        assertThat(result.entriesChecked()).isEqualTo(2);
    }

    @Test
    void a_removed_entry_is_found_by_the_verification() {
        append(organization, UUID.randomUUID(), "{\"n\":2}");
        append(organization, UUID.randomUUID(), "{\"n\":3}");
        List<AuditEntry> chain = chain(organization);

        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET session_replication_role = replica");
                statement.execute("DELETE FROM audit_log WHERE id = " + chain.get(1).id());
                statement.execute("SET session_replication_role = DEFAULT");
            }
            return null;
        });

        ChainVerification result = verify(organization);
        assertThat(result.valid()).isFalse();
        assertThat(result.brokenEntryId()).isEqualTo(chain.get(2).id());
    }

    private java.util.Optional<AuditEntry> append(UUID organizationId, UUID event, String afterValue) {
        return tx.execute(status -> auditLog.append(new NewAuditEntry(event, organizationId, "Dispatch",
                UUID.randomUUID(), "DispatchDeparted", null, Instant.now(), null, afterValue)));
    }

    private List<AuditEntry> chain(UUID organizationId) {
        List<AuditEntry> entries = new ArrayList<>();
        auditLog.forEachInChainOrder(organizationId, entries::add);
        return entries;
    }

    private ChainVerification verify(UUID organizationId) {
        ChainVerification.Verifier verifier = new ChainVerification.Verifier();
        auditLog.forEachInChainOrder(organizationId, verifier::accept);
        return verifier.result();
    }

    private boolean hasPrivilege(String privilege) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT has_table_privilege('audit_service', 'audit_log', ?)", Boolean.class, privilege));
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
