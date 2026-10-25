package pe.buildshield.audit.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.audit.domain.model.AuditEntry;
import pe.buildshield.audit.domain.model.AuditFilter;
import pe.buildshield.audit.domain.model.AuditLogRepository;
import pe.buildshield.audit.domain.model.AuditPage;
import pe.buildshield.audit.domain.model.NewAuditEntry;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * {@code audit_log} con JDBC: solo INSERT y SELECT (el rol del servicio no tiene más y un trigger rechaza UPDATE,
 * DELETE y TRUNCATE aunque alguien lo intente por fuera de este repositorio). Toda consulta filtra por la organización.
 *
 * <p>Anexar toma un candado por organización que dura hasta el fin de la transacción: la entrada nueva lee la huella
 * de la última, se encadena a ella y se inserta sin que otra de la misma organización se cuele en medio. Los ids de
 * una organización crecen en el orden de la cadena.
 */
@Repository
class JdbcAuditLogRepository implements AuditLogRepository {

    private static final RowMapper<AuditEntry> ROW = (rs, row) -> new AuditEntry(rs.getLong("id"),
            rs.getObject("event_id", UUID.class), rs.getObject("organization_id", UUID.class),
            rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class), rs.getString("action"),
            rs.getObject("actor_user_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
            rs.getString("before_value"), rs.getString("after_value"), rs.getString("prev_hash"),
            rs.getString("hash"));

    private static final String COLUMNS = """
            id, event_id, organization_id, aggregate_type, aggregate_id, action, actor_user_id, occurred_at,
            before_value::text AS before_value, after_value::text AS after_value, prev_hash, hash""";

    private final JdbcTemplate jdbc;

    JdbcAuditLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AuditEntry> append(NewAuditEntry entry) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, entry.organizationId().toString());
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM audit_log WHERE event_id = ?)",
                Boolean.class, entry.eventId()))) {
            return Optional.empty();
        }
        String prevHash = jdbc.query("SELECT hash FROM audit_log WHERE organization_id = ? ORDER BY id DESC LIMIT 1",
                (rs, row) -> rs.getString(1), entry.organizationId()).stream().findFirst()
                .orElse(AuditEntry.GENESIS_HASH);
        // El hash se calcula sobre el texto que la base devuelve del jsonb, que es el que se vuelve a leer al verificar.
        NewAuditEntry normalized = new NewAuditEntry(entry.eventId(), entry.organizationId(), entry.aggregateType(),
                entry.aggregateId(), entry.action(), entry.actorUserId(), entry.occurredAt(),
                normalize(entry.beforeValue()), normalize(entry.afterValue()));
        AuditEntry chained = AuditEntry.chain(normalized, prevHash);
        Long id = jdbc.queryForObject("""
                INSERT INTO audit_log (event_id, organization_id, aggregate_type, aggregate_id, action, actor_user_id,
                    occurred_at, before_value, after_value, prev_hash, hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)
                RETURNING id
                """, Long.class, chained.eventId(), chained.organizationId(), chained.aggregateType(),
                chained.aggregateId(), chained.action(), chained.actorUserId(), Timestamp.from(chained.occurredAt()),
                chained.beforeValue(), chained.afterValue(), chained.prevHash(), chained.hash());
        return Optional.of(new AuditEntry(id, chained.eventId(), chained.organizationId(), chained.aggregateType(),
                chained.aggregateId(), chained.action(), chained.actorUserId(), chained.occurredAt(),
                chained.beforeValue(), chained.afterValue(), chained.prevHash(), chained.hash()));
    }

    private String normalize(String json) {
        return json == null ? null : jdbc.queryForObject("SELECT CAST(? AS jsonb)::text", String.class, json);
    }

    @Override
    public AuditPage search(UUID organizationId, AuditFilter filter) {
        StringBuilder where = new StringBuilder(" WHERE organization_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(organizationId);
        condition(where, params, "action = ?", filter.action());
        condition(where, params, "aggregate_type = ?", filter.aggregateType());
        condition(where, params, "aggregate_id = ?", filter.aggregateId());
        condition(where, params, "actor_user_id = ?", filter.actorUserId());
        condition(where, params, "occurred_at >= ?", filter.from() == null ? null : Timestamp.from(filter.from()));
        condition(where, params, "occurred_at < ?", filter.to() == null ? null : Timestamp.from(filter.to()));
        Long total = jdbc.queryForObject("SELECT count(*) FROM audit_log" + where, Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(filter.size());
        pageParams.add(filter.offset());
        List<AuditEntry> items = jdbc.query("SELECT " + COLUMNS + " FROM audit_log" + where
                + " ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?", ROW, pageParams.toArray());
        return new AuditPage(items, filter.page(), filter.size(), total == null ? 0 : total);
    }

    @Override
    public List<AuditEntry> findByDispatch(UUID organizationId, UUID dispatchId) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM audit_log
                WHERE organization_id = ?
                  AND ((after_value ->> 'dispatchId') = ? OR (upper(aggregate_type) = 'DISPATCH' AND aggregate_id = ?))
                ORDER BY occurred_at, id
                """, ROW, organizationId, dispatchId.toString(), dispatchId);
    }

    @Override
    public Optional<UUID> findDispatchWorksite(UUID organizationId, UUID dispatchId) {
        return jdbc.query("""
                SELECT after_value ->> 'worksiteId' FROM audit_log
                WHERE organization_id = ?
                  AND ((after_value ->> 'dispatchId') = ? OR (upper(aggregate_type) = 'DISPATCH' AND aggregate_id = ?))
                  AND after_value ->> 'worksiteId' IS NOT NULL
                ORDER BY occurred_at, id LIMIT 1
                """, (rs, row) -> UUID.fromString(rs.getString(1)), organizationId, dispatchId.toString(),
                dispatchId).stream().findFirst();
    }

    @Override
    public int replay(UUID organizationId, Collection<String> actions, Consumer<AuditEntry> consumer) {
        if (actions.isEmpty()) {
            return 0;
        }
        return stream("WHERE organization_id = ? AND action = ANY (?) ORDER BY occurred_at, id", consumer,
                organizationId, actions.toArray(new String[0]));
    }

    @Override
    public int forEachInChainOrder(UUID organizationId, Consumer<AuditEntry> consumer) {
        return stream("WHERE organization_id = ? ORDER BY id", consumer, organizationId);
    }

    /** RowCallbackHandler: recorre fila por fila sin cargar todo el historial en memoria. */
    private int stream(String clause, Consumer<AuditEntry> consumer, Object... params) {
        AtomicInteger count = new AtomicInteger();
        jdbc.query("SELECT " + COLUMNS + " FROM audit_log " + clause, rs -> {
            consumer.accept(ROW.mapRow(rs, count.get()));
            count.incrementAndGet();
        }, params);
        return count.get();
    }

    private static void condition(StringBuilder where, List<Object> params, String sql, Object value) {
        if (value != null) {
            where.append(" AND ").append(sql);
            params.add(value);
        }
    }
}
