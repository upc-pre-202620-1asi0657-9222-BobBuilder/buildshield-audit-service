package pe.buildshield.audit.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Filtros de la consulta del registro; los nulos no filtran. Página desde 0. */
public record AuditFilter(String eventType, String aggregateType, String aggregateId, UUID actorId, String topic,
        Instant from, Instant to, int page, int size) {

    public static final int MAX_SIZE = 200;

    public AuditFilter {
        page = Math.max(page, 0);
        size = size < 1 ? 50 : Math.min(size, MAX_SIZE);
        eventType = blankToNull(eventType);
        aggregateType = blankToNull(aggregateType);
        aggregateId = blankToNull(aggregateId);
        topic = blankToNull(topic);
    }

    public long offset() {
        return (long) page * size;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
