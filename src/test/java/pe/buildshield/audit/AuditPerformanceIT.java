package pe.buildshield.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.buildshield.audit.application.CustodyExportService;
import pe.buildshield.audit.application.ReconciliationQueryService;
import pe.buildshield.audit.application.ReconciliationQueryService.ReconciliationFilter;
import pe.buildshield.audit.domain.model.AuditLogRepository;
import pe.buildshield.audit.domain.model.NewAuditEntry;
import pe.buildshield.audit.shared.tenant.TenantContext;
import pe.buildshield.audit.shared.tenant.TenantInfo;
import pe.buildshield.audit.support.AuditIntegrationTest;
import pe.buildshield.audit.support.TestDatabase;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RNF03: exportar la custodia de un despacho en menos de 10 segundos, y el reporte de conciliación armado desde
 * {@code audit_log} (sin read models) dentro del mismo presupuesto con un historial grande.
 */
@AuditIntegrationTest
class AuditPerformanceIT {

    private static final Duration LIMIT = Duration.ofSeconds(10);
    private static final int DISPATCHES = 40_000;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuditLogRepository auditLog;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    CustodyExportService exports;

    @Autowired
    ReconciliationQueryService reconciliation;

    private final UUID organization = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    @BeforeEach
    void clean() {
        TestDatabase.reset(jdbc);
    }

    @Test
    void a_custody_pdf_with_hundreds_of_steps_is_generated_in_under_ten_seconds() {
        UUID dispatch = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Instant start = Instant.parse("2026-11-03T12:00:00Z");
        tx.executeWithoutResult(status -> {
            for (int i = 0; i < 300; i++) {
                auditLog.append(new NewAuditEntry(UUID.randomUUID(), organization, "Dispatch", dispatch,
                        i % 2 == 0 ? "TransitIncidentReported" : "DispatchDepartureWeighed", admin,
                        start.plusSeconds(i), null, "{\"dispatchId\":\"" + dispatch + "\",\"manifestCode\":\"MAN-1\","
                        + "\"type\":\"DELAY\",\"observation\":\"Tráfico en la Panamericana\",\"grossKg\":12000,"
                        + "\"tareKg\":4000}"));
            }
        });

        long started = System.nanoTime();
        var file = TenantContext.callAs(new TenantInfo(organization, admin, "ADMINISTRATOR"),
                () -> exports.export(dispatch));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        System.out.printf("RNF03: PDF de custodia con 300 pasos en %d ms%n", elapsed.toMillis());
        assertThat(file.fileName()).startsWith("custodia-MAN-1-").endsWith(".pdf");
        assertThat(new String(file.content(), 0, 5)).isEqualTo("%PDF-");
        assertThat(elapsed).as("exportar la custodia").isLessThan(LIMIT);
    }

    @Test
    void the_reconciliation_report_over_a_large_history_is_built_from_the_log_in_under_ten_seconds() {
        // 40 000 salidas de despacho de dos líneas cada una (80 000 líneas), insertadas en bloque. Los SET LOCAL van en
        // la misma transacción que el INSERT para que valgan en esa conexión: sin trigger y sin el statement_timeout
        // de 5 s, que con la máquina cargada corta la carga de datos (lo medido es el reporte, no esta preparación).
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL statement_timeout = 0");
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    INSERT INTO audit_log (event_id, organization_id, aggregate_type, aggregate_id, action,
                        actor_user_id, occurred_at, after_value, prev_hash, hash)
                    SELECT gen_random_uuid(), ?, 'Dispatch', d.id, 'DispatchDeparted', ?,
                        TIMESTAMPTZ '2026-11-01 08:00:00+00' + (n || ' seconds')::interval,
                        jsonb_build_object('dispatchId', d.id, 'manifestCode', 'MAN-' || n,
                            'warehouseId', '00000000-0000-0000-0000-0000000000f1',
                            'worksiteId', '00000000-0000-0000-0000-0000000000a1',
                            'departedAt', to_char((TIMESTAMPTZ '2026-11-01 08:00:00+00' + (n || ' seconds')::interval)
                                AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                            'lines', jsonb_build_array(
                                jsonb_build_object('dispatchLineId', gen_random_uuid(), 'orderLineId',
                                    gen_random_uuid(), 'materialId', '00000000-0000-0000-0000-0000000000b1',
                                    'quantity', 10),
                                jsonb_build_object('dispatchLineId', gen_random_uuid(), 'orderLineId',
                                    gen_random_uuid(), 'materialId', '00000000-0000-0000-0000-0000000000b2',
                                    'quantity', 5))),
                        md5(n::text) || md5(n::text), md5(n::text) || md5('x' || n::text)
                    FROM (SELECT n, gen_random_uuid() AS id FROM generate_series(1, ?) AS n) d
                    """, organization, admin, DISPATCHES);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log", Long.class)).isEqualTo(DISPATCHES);

        long started = System.nanoTime();
        var report = TenantContext.callAs(new TenantInfo(organization, admin, "ADMINISTRATOR"),
                () -> reconciliation.report(new ReconciliationFilter(null, null, LocalDate.parse("2026-11-01"),
                        LocalDate.parse("2026-11-30"))));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        System.out.printf("RNF03: reporte de conciliación con %d despachos en %d ms%n", DISPATCHES, elapsed.toMillis());
        assertThat(report.summary().dispatchCount()).isEqualTo(DISPATCHES);
        assertThat(elapsed).as("armar el reporte de conciliación con %d despachos", DISPATCHES).isLessThan(LIMIT);
    }
}
