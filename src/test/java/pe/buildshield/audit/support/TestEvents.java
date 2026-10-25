package pe.buildshield.audit.support;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Sobres JSON de eventos de los demás servicios, como los publica su outbox (plan §2 y §5). */
public final class TestEvents {

    private final ObjectMapper json;

    public TestEvents(ObjectMapper json) {
        this.json = json;
    }

    public String envelope(UUID eventId, String eventType, UUID organizationId, String aggregateType,
            Object aggregateId, UUID actorId, Instant occurredAt, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("organizationId", organizationId);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId.toString());
        envelope.put("actorId", actorId);
        envelope.put("version", 1);
        envelope.put("payload", payload);
        try {
            return json.writeValueAsString(envelope);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public String userCreated(UUID organizationId, UUID userId, String fullName, String role) {
        return envelope(UUID.randomUUID(), "UserCreated", organizationId, "USER", userId, null, Instant.now(),
                map("userId", userId, "fullName", fullName, "email", userId + "@obra.pe", "role", role, "active", true));
    }

    public String staffAssigned(UUID organizationId, UUID assignmentId, UUID userId, UUID locationId) {
        return envelope(UUID.randomUUID(), "StaffAssigned", organizationId, "ASSIGNMENT", assignmentId, null,
                Instant.now(), map("assignmentId", assignmentId, "userId", userId, "locationId", locationId,
                        "locationType", "WORKSITE"));
    }

    /** Evento de ordering de un despacho, con dispatchId y worksiteId en el payload. */
    public String dispatchEvent(String eventType, UUID organizationId, UUID dispatchId, UUID worksiteId, UUID actorId,
            Instant at, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dispatchId", dispatchId);
        payload.put("worksiteId", worksiteId);
        payload.putAll(extra);
        String aggregateType = eventType.startsWith("Reception") || eventType.equals("ArrivalWeighed")
                ? "RECEPTION" : "DISPATCH";
        Object aggregateId = aggregateType.equals("DISPATCH") ? dispatchId
                : payload.getOrDefault("receptionId", UUID.randomUUID());
        return envelope(UUID.randomUUID(), eventType, organizationId, aggregateType, aggregateId, actorId, at, payload);
    }

    public String discrepancyEvent(String eventType, UUID organizationId, UUID discrepancyId, UUID dispatchId,
            UUID actorId, Instant at, String text) {
        return envelope(UUID.randomUUID(), eventType, organizationId, "DISCREPANCY", discrepancyId, actorId, at,
                map("discrepancyId", discrepancyId, "dispatchId", dispatchId, "materialName", "Cemento Portland",
                        "sku", "CEM-001", "status", "OPEN", "text", text));
    }

    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    public static List<Map<String, Object>> lines(Map<String, Object>... lines) {
        return List.of(lines);
    }
}
