package pe.buildshield.audit.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Una entrada de la bitácora de auditoría (agregado Audit Domain del C4): quién ({@code actorUserId}), qué
 * ({@code action} sobre un agregado), cuándo ({@code occurredAt}) y qué cambió ({@code beforeValue} y
 * {@code afterValue}). Es inmutable y queda encadenada a la anterior de su organización con una huella SHA-256:
 *
 * <pre>hash = SHA-256(prev_hash || event_id || organization_id || aggregate_type || aggregate_id || action
 *                || actor_user_id || occurred_at || after_value)</pre>
 *
 * Cada valor entra como texto: uuid en minúsculas con guiones, {@code occurred_at} en UTC con seis decimales
 * ({@code yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'}) y {@code after_value} tal como lo devuelve {@code jsonb::text} (vacío si
 * no hay). Alterar cualquiera de esos campos, o quitar o reordenar entradas, rompe la cadena.
 *
 * @param id        asignado por la base al anexar; nulo mientras no se guarda
 * @param prevHash  huella de la entrada anterior de la organización; {@link #GENESIS_HASH} en la primera
 * @param hash      huella de esta entrada
 */
public record AuditEntry(Long id, UUID eventId, UUID organizationId, String aggregateType, UUID aggregateId,
        String action, UUID actorUserId, Instant occurredAt, String beforeValue, String afterValue, String prevHash,
        String hash) {

    /** {@code prev_hash} de la primera entrada de cada organización: 64 ceros. */
    public static final String GENESIS_HASH = "0".repeat(64);

    /** Actor de las acciones que no provocó ningún usuario (procesos del sistema). */
    public static final UUID SYSTEM_USER_ID = new UUID(0L, 0L);

    private static final DateTimeFormatter OCCURRED_AT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    public AuditEntry {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(prevHash, "prevHash");
        Objects.requireNonNull(hash, "hash");
    }

    /** Encadena una acción nueva a la entrada anterior de su organización ({@link #GENESIS_HASH} si no hay). */
    public static AuditEntry chain(NewAuditEntry draft, String prevHash) {
        String hash = computeHash(prevHash, draft.eventId(), draft.organizationId(), draft.aggregateType(),
                draft.aggregateId(), draft.action(), draft.actorUserId(), draft.occurredAt(), draft.afterValue());
        return new AuditEntry(null, draft.eventId(), draft.organizationId(), draft.aggregateType(),
                draft.aggregateId(), draft.action(), draft.actorUserId(), draft.occurredAt(), draft.beforeValue(),
                draft.afterValue(), prevHash, hash);
    }

    public static String computeHash(String prevHash, UUID eventId, UUID organizationId, String aggregateType,
            UUID aggregateId, String action, UUID actorUserId, Instant occurredAt, String afterValue) {
        String input = prevHash + eventId + organizationId + aggregateType + aggregateId + action + actorUserId
                + OCCURRED_AT.format(occurredAt) + (afterValue == null ? "" : afterValue);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }

    /** ¿La huella guardada corresponde a los datos de la entrada? */
    public boolean hashMatches() {
        return hash.equals(computeHash(prevHash, eventId, organizationId, aggregateType, aggregateId, action,
                actorUserId, occurredAt, afterValue));
    }
}
