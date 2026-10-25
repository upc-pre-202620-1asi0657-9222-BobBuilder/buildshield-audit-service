package pe.buildshield.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.audit.domain.model.AssignmentDirectory;
import pe.buildshield.audit.domain.model.NewAuditEntry;
import pe.buildshield.audit.domain.model.AuditLogRepository;
import pe.buildshield.audit.domain.model.UserDirectory;
import pe.buildshield.audit.shared.messaging.EventEnvelope;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Registra cada acción relevante como entrada de la bitácora de auditoría (componente Audit Recorder del C4) y
 * mantiene las copias locales que la custodia necesita (usuarios y asignaciones), en la misma transacción. Cualquier
 * tipo de evento se registra, también los desconocidos. El estado posterior de la entrada ({@code after_value}) es el
 * payload del evento; los eventos no informan el estado anterior ({@code before_value} queda vacío).
 */
@Service
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final AuditLogRepository auditLog;
    private final UserDirectory users;
    private final AssignmentDirectory assignments;
    private final Clock clock;

    public AuditRecorder(AuditLogRepository auditLog, UserDirectory users, AssignmentDirectory assignments,
            Clock clock) {
        this.auditLog = auditLog;
        this.users = users;
        this.assignments = assignments;
        this.clock = clock;
    }

    /** @return {@code false} si el evento ya estaba en el registro */
    @Transactional
    public boolean record(EventEnvelope event) {
        JsonNode payload = event.payload() == null || event.payload().isNull()
                ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : event.payload();
        Instant occurredAt = event.occurredAt() == null ? clock.instant() : event.occurredAt();
        boolean appended = auditLog.append(new NewAuditEntry(event.eventId(), event.organizationId(),
                event.aggregateType() == null ? "UNKNOWN" : event.aggregateType(),
                aggregateId(event), event.eventType(), event.actorId(), occurredAt, null, payload.toString()))
                .isPresent();
        if (!appended) {
            log.debug("Evento {} ya registrado", event.eventId());
            return false;
        }
        updateCopies(event, payload);
        return true;
    }

    /** El sobre manda un uuid; si llega otra cosa se deriva uno estable del texto para no perder el evento. */
    static UUID aggregateId(EventEnvelope event) {
        String id = event.aggregateId();
        if (id == null || id.isBlank()) {
            return event.eventId();
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
        }
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
