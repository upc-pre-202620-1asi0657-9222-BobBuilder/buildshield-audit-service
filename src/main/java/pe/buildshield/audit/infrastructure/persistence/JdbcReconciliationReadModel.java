package pe.buildshield.audit.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import pe.buildshield.audit.domain.model.reconciliation.DiscrepancyCase;
import pe.buildshield.audit.domain.model.reconciliation.LineStatus;
import pe.buildshield.audit.domain.model.reconciliation.MaterialInfo;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationCriteria;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationLine;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReadModel;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read model de conciliación con JDBC: {@code location_projection}, {@code material_projection},
 * {@code reconciliation_line} y {@code reconciliation_discrepancy}. Toda consulta filtra por la organización.
 */
@Repository
class JdbcReconciliationReadModel implements ReconciliationReadModel {

    private static final RowMapper<ReconciliationLine> LINE = (rs, row) -> new ReconciliationLine(
            rs.getObject("dispatch_line_id", UUID.class), rs.getObject("dispatch_id", UUID.class),
            rs.getObject("order_line_id", UUID.class), rs.getString("manifest_code"),
            rs.getObject("warehouse_id", UUID.class), rs.getObject("worksite_id", UUID.class),
            rs.getObject("material_id", UUID.class), rs.getString("sku"), rs.getString("material_name"),
            rs.getString("unit"), rs.getBigDecimal("dispatched_qty"), rs.getBigDecimal("received_qty"),
            rs.getBigDecimal("shrinkage_percent"), rs.getBigDecimal("tolerance_percent"),
            (Boolean) rs.getObject("within_tolerance"), LineStatus.valueOf(rs.getString("status")),
            instant(rs, "departed_at"), instant(rs, "received_at"));

    private static final RowMapper<DiscrepancyCase> DISCREPANCY = (rs, row) -> new DiscrepancyCase(
            rs.getObject("discrepancy_id", UUID.class), rs.getObject("dispatch_id", UUID.class),
            rs.getString("manifest_code"), rs.getObject("worksite_id", UUID.class),
            rs.getObject("warehouse_id", UUID.class), rs.getObject("material_id", UUID.class), rs.getString("sku"),
            rs.getString("material_name"), rs.getString("type"), rs.getBigDecimal("quantity"), rs.getString("status"),
            instant(rs, "opened_at"));

    /** Fecha que ubica una línea en el periodo (igual que {@link ReconciliationLine#periodDate()}). */
    private static final String PERIOD_DATE = "COALESCE(%s.departed_at, %s.received_at)";

    private final JdbcTemplate jdbc;

    JdbcReconciliationReadModel(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveLocation(UUID organizationId, UUID locationId, String type, String name, Instant at) {
        jdbc.update("""
                INSERT INTO location_projection (location_id, organization_id, type, name, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (location_id) DO UPDATE SET type = EXCLUDED.type, name = EXCLUDED.name,
                    updated_at = EXCLUDED.updated_at
                """, locationId, organizationId, type, name, Timestamp.from(at));
    }

    @Override
    public void saveMaterial(UUID organizationId, MaterialInfo material, Instant at) {
        jdbc.update("""
                INSERT INTO material_projection (material_id, organization_id, sku, name, unit, tolerance_percent,
                    updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (material_id) DO UPDATE SET sku = COALESCE(EXCLUDED.sku, material_projection.sku),
                    name = COALESCE(EXCLUDED.name, material_projection.name),
                    unit = COALESCE(EXCLUDED.unit, material_projection.unit),
                    tolerance_percent = COALESCE(EXCLUDED.tolerance_percent, material_projection.tolerance_percent),
                    updated_at = EXCLUDED.updated_at
                """, material.materialId(), organizationId, material.sku(), material.name(), material.unit(),
                material.tolerancePercent(), Timestamp.from(at));
    }

    @Override
    public Optional<ReconciliationLine> findLine(UUID organizationId, UUID dispatchLineId) {
        return jdbc.query("SELECT * FROM reconciliation_line WHERE organization_id = ? AND dispatch_line_id = ?",
                LINE, organizationId, dispatchLineId).stream().findFirst();
    }

    @Override
    public List<ReconciliationLine> findLinesOfDispatch(UUID organizationId, UUID dispatchId) {
        return jdbc.query("""
                SELECT * FROM reconciliation_line WHERE organization_id = ? AND dispatch_id = ?
                ORDER BY dispatch_line_id
                """, LINE, organizationId, dispatchId);
    }

    @Override
    public void saveLine(UUID organizationId, ReconciliationLine line, Instant at) {
        jdbc.update("""
                INSERT INTO reconciliation_line (dispatch_line_id, organization_id, dispatch_id, order_line_id,
                    manifest_code, warehouse_id, worksite_id, material_id, sku, material_name, unit, dispatched_qty,
                    received_qty, shrinkage_percent, tolerance_percent, within_tolerance, status, departed_at,
                    received_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (dispatch_line_id) DO UPDATE SET dispatch_id = EXCLUDED.dispatch_id,
                    order_line_id = EXCLUDED.order_line_id, manifest_code = EXCLUDED.manifest_code,
                    warehouse_id = EXCLUDED.warehouse_id, worksite_id = EXCLUDED.worksite_id,
                    material_id = EXCLUDED.material_id, sku = EXCLUDED.sku, material_name = EXCLUDED.material_name,
                    unit = EXCLUDED.unit, dispatched_qty = EXCLUDED.dispatched_qty,
                    received_qty = EXCLUDED.received_qty, shrinkage_percent = EXCLUDED.shrinkage_percent,
                    tolerance_percent = EXCLUDED.tolerance_percent, within_tolerance = EXCLUDED.within_tolerance,
                    status = EXCLUDED.status, departed_at = EXCLUDED.departed_at, received_at = EXCLUDED.received_at,
                    updated_at = EXCLUDED.updated_at
                """, line.dispatchLineId(), organizationId, line.dispatchId(), line.orderLineId(),
                line.manifestCode(), line.warehouseId(), line.worksiteId(), line.materialId(), line.sku(),
                line.materialName(), line.unit(), line.dispatched(), line.received(), line.shrinkagePercent(),
                line.tolerancePercent(), line.withinTolerance(), line.status().name(), timestamp(line.departedAt()),
                timestamp(line.receivedAt()), Timestamp.from(at));
    }

    @Override
    public Optional<DiscrepancyCase> findDiscrepancy(UUID organizationId, UUID discrepancyId) {
        return jdbc.query("SELECT * FROM reconciliation_discrepancy WHERE organization_id = ? AND discrepancy_id = ?",
                DISCREPANCY, organizationId, discrepancyId).stream().findFirst();
    }

    @Override
    public void saveDiscrepancy(UUID organizationId, DiscrepancyCase discrepancy, Instant at) {
        jdbc.update("""
                INSERT INTO reconciliation_discrepancy (discrepancy_id, organization_id, dispatch_id, manifest_code,
                    worksite_id, warehouse_id, material_id, sku, material_name, type, quantity, status, opened_at,
                    updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (discrepancy_id) DO UPDATE SET dispatch_id = EXCLUDED.dispatch_id,
                    manifest_code = EXCLUDED.manifest_code, worksite_id = EXCLUDED.worksite_id,
                    warehouse_id = EXCLUDED.warehouse_id, material_id = EXCLUDED.material_id, sku = EXCLUDED.sku,
                    material_name = EXCLUDED.material_name, type = EXCLUDED.type, quantity = EXCLUDED.quantity,
                    status = EXCLUDED.status, opened_at = EXCLUDED.opened_at, updated_at = EXCLUDED.updated_at
                """, discrepancy.discrepancyId(), organizationId, discrepancy.dispatchId(), discrepancy.manifestCode(),
                discrepancy.worksiteId(), discrepancy.warehouseId(), discrepancy.materialId(), discrepancy.sku(),
                discrepancy.materialName(), discrepancy.type(), discrepancy.quantity(), discrepancy.status(),
                Timestamp.from(discrepancy.openedAt()), Timestamp.from(at));
    }

    @Override
    public void clear(UUID organizationId) {
        for (String table : List.of("reconciliation_line", "reconciliation_discrepancy", "location_projection",
                "material_projection")) {
            jdbc.update("DELETE FROM " + table + " WHERE organization_id = ?", organizationId);
        }
    }

    @Override
    public List<ReconciliationLine> findLines(UUID organizationId, ReconciliationCriteria criteria) {
        List<Object> params = new ArrayList<>();
        params.add(organizationId);
        String where = locations("l", criteria, params) + period("l", criteria, params);
        return jdbc.query("SELECT * FROM reconciliation_line l WHERE l.organization_id = ? AND l.status <> 'CANCELLED'"
                + where + " ORDER BY l.departed_at NULLS LAST, l.dispatch_id, l.dispatch_line_id", LINE,
                params.toArray());
    }

    @Override
    public List<DiscrepancyCase> findOpenDiscrepancies(UUID organizationId, ReconciliationCriteria criteria) {
        List<Object> params = new ArrayList<>();
        params.add(organizationId);
        String where = locations("d", criteria, params);
        String linePeriod = period("l", criteria, params);
        List<Object> openedParams = new ArrayList<>();
        String openedPeriod = openedPeriod(criteria, openedParams);
        params.addAll(openedParams);
        return jdbc.query("""
                SELECT d.* FROM reconciliation_discrepancy d
                WHERE d.organization_id = ? AND d.status NOT IN ('ACCEPTED', 'RESOLVED')""" + where + """
                  AND (EXISTS (SELECT 1 FROM reconciliation_line l
                               WHERE l.organization_id = d.organization_id AND l.dispatch_id = d.dispatch_id
                                 AND l.status <> 'CANCELLED'""" + linePeriod + """
                               )
                       OR (NOT EXISTS (SELECT 1 FROM reconciliation_line l
                                       WHERE l.organization_id = d.organization_id AND l.dispatch_id = d.dispatch_id)
                           """ + openedPeriod + """
                           ))
                ORDER BY d.opened_at, d.discrepancy_id
                """, DISCREPANCY, params.toArray());
    }

    @Override
    public Map<UUID, String> locationNames(UUID organizationId, Collection<UUID> locationIds) {
        Map<UUID, String> names = new HashMap<>();
        if (locationIds.isEmpty()) {
            return names;
        }
        jdbc.query("SELECT location_id, name FROM location_projection WHERE organization_id = ? AND location_id = ANY (?)",
                rs -> {
                    names.put(rs.getObject("location_id", UUID.class), rs.getString("name"));
                }, organizationId, locationIds.toArray(new UUID[0]));
        return names;
    }

    @Override
    public Map<UUID, MaterialInfo> materials(UUID organizationId, Collection<UUID> materialIds) {
        Map<UUID, MaterialInfo> materials = new HashMap<>();
        if (materialIds.isEmpty()) {
            return materials;
        }
        jdbc.query("SELECT * FROM material_projection WHERE organization_id = ? AND material_id = ANY (?)", rs -> {
            UUID id = rs.getObject("material_id", UUID.class);
            materials.put(id, new MaterialInfo(id, rs.getString("sku"), rs.getString("name"), rs.getString("unit"),
                    rs.getBigDecimal("tolerance_percent")));
        }, organizationId, materialIds.toArray(new UUID[0]));
        return materials;
    }

    private static String locations(String alias, ReconciliationCriteria criteria, List<Object> params) {
        StringBuilder where = new StringBuilder();
        in(where, params, alias + ".worksite_id", criteria.worksites());
        in(where, params, alias + ".warehouse_id", criteria.warehouses());
        return where.toString();
    }

    private static void in(StringBuilder where, List<Object> params, String column, Set<UUID> values) {
        if (values != null) {
            where.append(" AND ").append(column).append(" = ANY (?)");
            params.add(values.toArray(new UUID[0]));
        }
    }

    private static String period(String alias, ReconciliationCriteria criteria, List<Object> params) {
        StringBuilder where = new StringBuilder();
        String date = PERIOD_DATE.formatted(alias, alias);
        if (criteria.from() != null) {
            where.append(" AND ").append(date).append(" >= ?");
            params.add(Timestamp.from(criteria.from()));
        }
        if (criteria.to() != null) {
            where.append(" AND ").append(date).append(" < ?");
            params.add(Timestamp.from(criteria.to()));
        }
        return where.toString();
    }

    private static String openedPeriod(ReconciliationCriteria criteria, List<Object> params) {
        StringBuilder where = new StringBuilder();
        if (criteria.from() != null) {
            where.append(" AND d.opened_at >= ?");
            params.add(Timestamp.from(criteria.from()));
        }
        if (criteria.to() != null) {
            where.append(" AND d.opened_at < ?");
            params.add(Timestamp.from(criteria.to()));
        }
        return where.toString();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
