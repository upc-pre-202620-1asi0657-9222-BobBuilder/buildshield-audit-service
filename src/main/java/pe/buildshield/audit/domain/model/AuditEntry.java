package pe.buildshield.audit.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Un evento de integración tal como quedó en el registro de solo anexado.
 *
 * @param payload JSON del payload del evento, sin cambios
 */
public record AuditEntry(UUID id, UUID eventId, String topic, String eventType, String aggregateType,
        String aggregateId, UUID organizationId, UUID actorId, Instant occurredAt, Instant recordedAt,
        String correlationId, String payload) {

    public AuditEntry {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(payload, "payload");
    }
}
