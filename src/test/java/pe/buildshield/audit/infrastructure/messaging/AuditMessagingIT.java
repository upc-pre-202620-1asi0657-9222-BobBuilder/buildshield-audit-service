package pe.buildshield.audit.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import pe.buildshield.audit.application.AuditQueryService;
import pe.buildshield.audit.domain.model.AuditFilter;
import pe.buildshield.audit.shared.tenant.TenantContext;
import pe.buildshield.audit.shared.tenant.TenantInfo;
import pe.buildshield.audit.support.AuditIntegrationTest;
import pe.buildshield.audit.support.KafkaReadiness;
import pe.buildshield.audit.support.TestDatabase;
import pe.buildshield.audit.support.TestEvents;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Los seis tópicos llegan al registro de solo anexado por Kafka embebido, cada evento una sola vez aunque se
 * entregue dos veces, y los de usuarios y asignaciones actualizan las copias locales.
 */
@AuditIntegrationTest
class AuditMessagingIT {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AuditQueryService queries;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @BeforeEach
    void clean() {
        TestDatabase.reset(jdbc);
        KafkaReadiness.awaitAssignments(listeners);
    }

    @Test
    void events_from_all_six_topics_are_recorded_once() {
        TestEvents events = new TestEvents(json);
        UUID organization = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID worksite = UUID.randomUUID();
        UUID assignment = UUID.randomUUID();
        UUID dispatch = UUID.randomUUID();
        String userCreated = events.userCreated(organization, user, "Rosa Quispe", "SITE_MANAGER");
        String departed = events.dispatchEvent("DispatchDeparted", organization, dispatch, worksite, user,
                Instant.now(), Map.of("campoNuevo", 1));

        kafka.send("buildshield.iam", user.toString(), userCreated);
        kafka.send("buildshield.iam", user.toString(), userCreated);
        kafka.send("buildshield.organization", assignment.toString(),
                events.staffAssigned(organization, assignment, user, worksite));
        kafka.send("buildshield.inventory", "s", events.envelope(UUID.randomUUID(), "StockReserved", organization,
                "STOCK", "s", null, Instant.now(), Map.of("orderId", UUID.randomUUID())));
        kafka.send("buildshield.ordering", dispatch.toString(), departed);
        kafka.send("buildshield.ordering", dispatch.toString(), departed);
        kafka.send("buildshield.discrepancy", "d", events.discrepancyEvent("DiscrepancyOpened", organization,
                UUID.randomUUID(), dispatch, user, Instant.now(), "Faltan 3 bolsas"));
        kafka.send("buildshield.subscription", organization.toString(), events.envelope(UUID.randomUUID(),
                "SubscriptionActivated", organization, "SUBSCRIPTION", organization, null, Instant.now(),
                Map.of("organizationId", organization, "plan", "FREE", "status", "TRIAL")));

        await().atMost(Duration.ofSeconds(30)).until(() -> count(organization) == 6);
        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).until(() -> count(organization) == 6);
        assertThat(jdbc.queryForList("SELECT DISTINCT topic FROM audit_log WHERE organization_id = ?", String.class,
                organization)).containsExactlyInAnyOrder("buildshield.iam", "buildshield.organization",
                "buildshield.inventory", "buildshield.ordering", "buildshield.discrepancy", "buildshield.subscription");
        assertThat(jdbc.queryForObject("SELECT full_name FROM user_projection WHERE user_id = ?", String.class, user))
                .isEqualTo("Rosa Quispe");
        assertThat(jdbc.queryForObject("SELECT location_id FROM assignment_projection WHERE assignment_id = ?",
                UUID.class, assignment)).isEqualTo(worksite);
        assertThat(jdbc.queryForObject("SELECT payload ->> 'campoNuevo' FROM audit_log WHERE event_type = ?",
                String.class, "DispatchDeparted")).isEqualTo("1");

        var page = TenantContext.callAs(new TenantInfo(organization, UUID.randomUUID(), "ADMINISTRATOR"),
                () -> queries.search(new AuditFilter(null, "DISPATCH", dispatch.toString(), null, null, null, null,
                        0, 10)));
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).actorName()).isEqualTo("Rosa Quispe");
        var byTopic = TenantContext.callAs(new TenantInfo(organization, UUID.randomUUID(), "ADMINISTRATOR"),
                () -> queries.search(new AuditFilter(null, null, null, user, "buildshield.discrepancy",
                        Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600), 0, 10)));
        assertThat(byTopic.total()).isEqualTo(1);
        assertThat(byTopic.items().get(0).entry().eventType()).isEqualTo("DiscrepancyOpened");
    }

    private long count(UUID organization) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE organization_id = ?", Long.class,
                organization);
        return count == null ? 0 : count;
    }
}
