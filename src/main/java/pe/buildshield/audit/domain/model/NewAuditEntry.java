package pe.buildshield.audit.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Acción por anexar a la bitácora, todavía sin huella. Se normaliza para que lo que se guarda y lo que se vuelve a
 * leer den la misma huella: el actor ausente es {@link AuditEntry#SYSTEM_USER_ID}, {@code aggregateType} y
 * {@code action} se acortan a 20 y 40 caracteres (los máximos del ER) y {@code occurredAt} se redondea hacia abajo a
 * microsegundos, la precisión de la base.
 *
 * @param beforeValue JSON del estado anterior; nulo si el evento no lo informa
 * @param afterValue  JSON del estado posterior (el payload del evento)
 */
public record NewAuditEntry(UUID eventId, UUID organizationId, String aggregateType, UUID aggregateId, String action,
        UUID actorUserId, Instant occurredAt, String beforeValue, String afterValue) {

    public static final int MAX_AGGREGATE_TYPE = 20;
    public static final int MAX_ACTION = 40;

    public NewAuditEntry {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        aggregateType = limit(Objects.requireNonNull(aggregateType, "aggregateType"), MAX_AGGREGATE_TYPE);
        action = limit(Objects.requireNonNull(action, "action"), MAX_ACTION);
        actorUserId = actorUserId == null ? AuditEntry.SYSTEM_USER_ID : actorUserId;
        occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
