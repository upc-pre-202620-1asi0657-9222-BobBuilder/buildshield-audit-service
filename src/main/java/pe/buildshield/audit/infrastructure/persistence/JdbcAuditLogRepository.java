package pe.buildshield.audit.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import pe.buildshield.audit.domain.model.AuditEntry;
import pe.buildshield.audit.domain.model.AuditFilter;
import pe.buildshield.audit.domain.model.AuditLogRepository;
import pe.buildshield.audit.domain.model.AuditPage;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * {@code audit_log} con JDBC: solo INSERT y SELECT. El trigger de la base rechaza UPDATE, DELETE y TRUNCATE aunque
 * alguien lo intente por fuera de este repositorio. Toda consulta filtra por la organización.
 */
@Repository
class JdbcAuditLogRepository implements AuditLogRepository {

    private static final RowMapper<AuditEntry> ROW = (rs, row) -> new AuditEntry(rs.getObject("id", UUID.class),
            rs.getObject("event_id", UUID.class), rs.getString("topic"), rs.getString("event_type"),
            rs.getString("aggregate_type"), rs.getString("aggregate_id"), rs.getObject("organization_id", UUID.class),
            rs.getObject("actor_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
            rs.getTimestamp("recorded_at").toInstant(), rs.getString("correlation_id"), rs.getString("payload"));

    private static final String COLUMNS = """
            id, event_id, topic, event_type, aggregate_type, aggregate_id, organization_id, actor_id, occurred_at,
            recorded_at, correlation_id, payload""";

    private final JdbcTemplate jdbc;

    JdbcAuditLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean append(AuditEntry entry) {
        return jdbc.update("""
                INSERT INTO audit_log (id, event_id, topic, event_type, aggregate_type, aggregate_id, organization_id,
                    actor_id, occurred_at, recorded_at, correlation_id, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                ON CONFLICT (event_id) DO NOTHING
                """, entry.id(), entry.eventId(), entry.topic(), entry.eventType(), entry.aggregateType(),
                entry.aggregateId(), entry.organizationId(), entry.actorId(), Timestamp.from(entry.occurredAt()),
                Timestamp.from(entry.recordedAt()), entry.correlationId(), entry.payload()) == 1;
    }

    @Override
    public AuditPage search(UUID organizationId, AuditFilter filter) {
        StringBuilder where = new StringBuilder(" WHERE organization_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(organizationId);
        condition(where, params, "event_type = ?", filter.eventType());
        condition(where, params, "aggregate_type = ?", filter.aggregateType());
        condition(where, params, "aggregate_id = ?", filter.aggregateId());
        condition(where, params, "actor_id = ?", filter.actorId());
        condition(where, params, "topic = ?", filter.topic());
        condition(where, params, "occurred_at >= ?", filter.from() == null ? null : Timestamp.from(filter.from()));
        condition(where, params, "occurred_at < ?", filter.to() == null ? null : Timestamp.from(filter.to()));
        Long total = jdbc.queryForObject("SELECT count(*) FROM audit_log" + where, Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(filter.size());
        pageParams.add(filter.offset());
        List<AuditEntry> items = jdbc.query("SELECT " + COLUMNS + " FROM audit_log" + where
                + " ORDER BY occurred_at DESC, recorded_at DESC, id DESC LIMIT ? OFFSET ?", ROW, pageParams.toArray());
        return new AuditPage(items, filter.page(), filter.size(), total == null ? 0 : total);
    }

    @Override
    public List<AuditEntry> findByDispatch(UUID organizationId, UUID dispatchId) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM audit_log
                WHERE organization_id = ?
                  AND ((payload ->> 'dispatchId') = ? OR (aggregate_type = 'DISPATCH' AND aggregate_id = ?))
                ORDER BY occurred_at, recorded_at, id
                """, ROW, organizationId, dispatchId.toString(), dispatchId.toString());
    }

    @Override
    public Optional<UUID> findDispatchWorksite(UUID organizationId, UUID dispatchId) {
        return jdbc.query("""
                SELECT payload ->> 'worksiteId' FROM audit_log
                WHERE organization_id = ?
                  AND ((payload ->> 'dispatchId') = ? OR (aggregate_type = 'DISPATCH' AND aggregate_id = ?))
                  AND payload ->> 'worksiteId' IS NOT NULL
                ORDER BY occurred_at LIMIT 1
                """, (rs, row) -> UUID.fromString(rs.getString(1)), organizationId, dispatchId.toString(),
                dispatchId.toString()).stream().findFirst();
    }

    @Override
    public int replay(UUID organizationId, Collection<String> eventTypes, Consumer<AuditEntry> action) {
        if (eventTypes.isEmpty()) {
            return 0;
        }
        AtomicInteger count = new AtomicInteger();
        // RowCallbackHandler: recorre fila por fila sin cargar todo el historial en memoria.
        jdbc.query("SELECT " + COLUMNS + """
                 FROM audit_log
                WHERE organization_id = ? AND event_type = ANY (?)
                ORDER BY occurred_at, recorded_at, id
                """, rs -> {
                    action.accept(ROW.mapRow(rs, count.get()));
                    count.incrementAndGet();
                }, organizationId, eventTypes.toArray(new String[0]));
        return count.get();
    }

    private static void condition(StringBuilder where, List<Object> params, String sql, Object value) {
        if (value != null) {
            where.append(" AND ").append(sql);
            params.add(value);
        }
    }
}
