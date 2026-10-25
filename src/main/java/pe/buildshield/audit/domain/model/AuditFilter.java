package pe.buildshield.audit.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Filtros de la consulta de la bitácora (usuario, fecha y entidad); los nulos no filtran. Página desde 0. */
public record AuditFilter(String action, String aggregateType, UUID aggregateId, UUID actorUserId, Instant from,
        Instant to, int page, int size) {

    public static final int MAX_SIZE = 200;

    public AuditFilter {
        page = Math.max(page, 0);
        size = size < 1 ? 50 : Math.min(size, MAX_SIZE);
        action = blankToNull(action);
        aggregateType = blankToNull(aggregateType);
    }

    public long offset() {
        return (long) page * size;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
