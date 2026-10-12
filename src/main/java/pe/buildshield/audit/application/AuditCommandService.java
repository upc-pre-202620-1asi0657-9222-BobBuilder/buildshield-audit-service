package pe.buildshield.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.audit.domain.model.AssignmentDirectory;
import pe.buildshield.audit.domain.model.AuditEntry;
import pe.buildshield.audit.domain.model.AuditLogRepository;
import pe.buildshield.audit.domain.model.UserDirectory;
import pe.buildshield.audit.shared.correlation.CorrelationId;
import pe.buildshield.audit.shared.messaging.EventEnvelope;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Registra cada evento de integración en el registro de solo anexado y mantiene las copias locales que la
 * custodia necesita (usuarios y asignaciones) y el read model de conciliación (US47), en la misma transacción.
 * Cualquier tipo de evento se registra, también los desconocidos.
 */
@Service
public class AuditCommandService {

    private static final Logger log = LoggerFactory.getLogger(AuditCommandService.class);

    private final AuditLogRepository auditLog;
    private final UserDirectory users;
    private final AssignmentDirectory assignments;
    private final ReconciliationProjector reconciliation;
    private final Clock clock;

    public AuditCommandService(AuditLogRepository auditLog, UserDirectory users, AssignmentDirectory assignments,
            ReconciliationProjector reconciliation, Clock clock) {
        this.auditLog = auditLog;
        this.users = users;
        this.assignments = assignments;
        this.reconciliation = reconciliation;
        this.clock = clock;
    }

    /** @return {@code false} si el evento ya estaba en el registro */
    @Transactional
    public boolean record(String topic, EventEnvelope event) {
        JsonNode payload = event.payload() == null || event.payload().isNull()
                ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : event.payload();
        Instant occurredAt = event.occurredAt() == null ? clock.instant() : event.occurredAt();
        boolean appended = auditLog.append(new AuditEntry(UUID.randomUUID(), event.eventId(), topic, event.eventType(),
                event.aggregateType(), event.aggregateId(), event.organizationId(), event.actorId(), occurredAt,
                clock.instant(), MDC.get(CorrelationId.MDC_KEY), payload.toString()));
        if (!appended) {
            log.debug("Evento {} ya registrado", event.eventId());
            return false;
        }
        updateCopies(event, payload);
        if (reconciliation.handles(event.eventType())) {
            reconciliation.apply(event.eventType(), event.organizationId(), event.aggregateId(), occurredAt, payload);
        }
        return true;
    }

    private void updateCopies(EventEnvelope event, JsonNode payload) {
        switch (event.eventType()) {
            case AuditEvents.USER_CREATED, AuditEvents.USER_UPDATED -> {
                UUID userId = uuid(payload, "userId");
                if (userId != null) {
                    users.upsert(event.organizationId(), userId, text(payload, "fullName"), text(payload, "email"),
                            payload.path("role").asText(""), payload.path("active").asBoolean(true));
                }
            }
            case AuditEvents.STAFF_ASSIGNED -> {
                UUID assignmentId = uuid(payload, "assignmentId");
                UUID userId = uuid(payload, "userId");
                UUID locationId = uuid(payload, "locationId");
                if (assignmentId != null && userId != null && locationId != null) {
                    assignments.assign(event.organizationId(), assignmentId, userId, locationId,
                            payload.path("locationType").asText(""));
                }
            }
            case AuditEvents.STAFF_UNASSIGNED -> {
                UUID assignmentId = uuid(payload, "assignmentId");
                if (assignmentId != null) {
                    assignments.unassign(assignmentId);
                }
            }
            default -> {
                // Solo se registra.
            }
        }
    }

    static UUID uuid(JsonNode payload, String field) {
        if (!payload.hasNonNull(field)) {
            return null;
        }
        try {
            return UUID.fromString(payload.get(field).asText());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static String text(JsonNode payload, String field) {
        return payload.hasNonNull(field) ? payload.get(field).asText() : null;
    }
}
