package pe.buildshield.audit.domain.model.reconciliation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Reporte de conciliación por obra, almacén y periodo (US47). Una fila por obra, almacén y material con lo despachado,
 * lo recibido, lo que falta recibir y la merma de lo ya conforme comparada con la tolerancia del material; totales por
 * unidad (las cantidades de unidades distintas no se suman entre sí) y los casos pendientes: despachos sin conformidad
 * y discrepancias abiertas.
 *
 * <p>Merma de una fila = suma de (despachado - recibido) de sus líneas conformes. Merma % = merma / despachado de esas
 * líneas × 100, con 2 decimales (la misma fórmula de ordering, QAS01). Supera la tolerancia si la merma % es mayor.
 */
public record ReconciliationReport(Filters filters, Instant generatedAt, String generatedBy, List<MaterialRow> rows,
        List<UnitTotal> totalsByUnit, Summary summary, List<PendingCase> pendingCases) {

    public static final String NO_UNIT = "Sin unidad";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public ReconciliationReport {
        rows = List.copyOf(rows);
        totalsByUnit = List.copyOf(totalsByUnit);
        pendingCases = List.copyOf(pendingCases);
    }

    /** Filtros aplicados, con los nombres de la obra y el almacén elegidos (nulos: todos). */
    public record Filters(UUID worksiteId, String worksiteName, UUID warehouseId, String warehouseName,
            LocalDate from, LocalDate to) {
    }

    public enum ToleranceStatus {
        WITHIN("Dentro de la tolerancia"),
        EXCEEDED("Supera la tolerancia"),
        NOT_RECEIVED("Sin conformidad");

        private final String label;

        ToleranceStatus(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param confirmedDispatched lo despachado en las líneas que ya tienen conformidad (base de la merma %)
     * @param pendingReception    lo despachado que todavía no llega o no se confirma
     * @param shrinkage           merma en cantidad de las líneas conformes; nula sin conformidad
     */
    public record MaterialRow(UUID worksiteId, String worksiteName, UUID warehouseId, String warehouseName,
            UUID materialId, String sku, String materialName, String unit, int dispatchCount, BigDecimal dispatched,
            BigDecimal received, BigDecimal pendingReception, BigDecimal confirmedDispatched, BigDecimal shrinkage,
            BigDecimal shrinkagePercent, BigDecimal tolerancePercent, ToleranceStatus toleranceStatus,
            int linesOutOfTolerance, int openDiscrepancies) {
    }

    public record UnitTotal(String unit, BigDecimal dispatched, BigDecimal received, BigDecimal pendingReception,
            BigDecimal shrinkage, BigDecimal shrinkagePercent) {
    }

    /**
     * @param pendingReceptions despachos con material todavía en tránsito o con recepción parcial
     */
    public record Summary(int dispatchCount, int pendingReceptions, int materialRows, int rowsExceedingTolerance,
            int linesOutOfTolerance, int openDiscrepancies) {
    }

    public enum PendingCaseType {
        RECEPTION_PENDING("Recepción pendiente"),
        OPEN_DISCREPANCY("Discrepancia abierta");

        private final String label;

        PendingCaseType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Despacho sin conformidad o discrepancia abierta, del más antiguo al más reciente. */
    public record PendingCase(PendingCaseType type, UUID dispatchId, String manifestCode, UUID discrepancyId,
            String worksiteName, String warehouseName, String material, String status, Instant since,
            String detail) {
    }

    public static ReconciliationReport build(Filters filters, Instant generatedAt, String generatedBy,
            List<ReconciliationLine> lines, List<DiscrepancyCase> discrepancies, Map<UUID, String> locationNames,
            Map<UUID, MaterialInfo> materials) {
        List<ReconciliationLine> active = lines.stream().filter(line -> line.status() != LineStatus.CANCELLED)
                .toList();
        Map<GroupKey, List<ReconciliationLine>> groups = new LinkedHashMap<>();
        active.forEach(line -> groups.computeIfAbsent(new GroupKey(line.worksiteId(), line.warehouseId(),
                line.materialId()), key -> new ArrayList<>()).add(line));

        List<MaterialRow> rows = new ArrayList<>();
        groups.forEach((key, group) -> rows.add(row(key, group, discrepancies, locationNames,
                materials.get(key.materialId()))));
        rows.sort(Comparator.comparing(MaterialRow::worksiteName, Comparator.nullsLast(String::compareTo))
                .thenComparing(MaterialRow::warehouseName, Comparator.nullsLast(String::compareTo))
                .thenComparing(MaterialRow::sku, Comparator.nullsLast(String::compareTo))
                .thenComparing(MaterialRow::materialName, Comparator.nullsLast(String::compareTo)));

        Set<UUID> dispatches = new LinkedHashSet<>();
        Set<UUID> pendingDispatches = new LinkedHashSet<>();
        active.forEach(line -> {
            dispatches.add(line.dispatchId());
            if (line.status().pendingReception()) {
                pendingDispatches.add(line.dispatchId());
            }
        });
        Summary summary = new Summary(dispatches.size(), pendingDispatches.size(), rows.size(),
                (int) rows.stream().filter(row -> row.toleranceStatus() == ToleranceStatus.EXCEEDED).count(),
                rows.stream().mapToInt(MaterialRow::linesOutOfTolerance).sum(), discrepancies.size());

        return new ReconciliationReport(filters, generatedAt, generatedBy, rows, totalsByUnit(rows), summary,
                pendingCases(active, pendingDispatches, discrepancies, locationNames));
    }

    private static MaterialRow row(GroupKey key, List<ReconciliationLine> group, List<DiscrepancyCase> discrepancies,
            Map<UUID, String> locationNames, MaterialInfo material) {
        Set<UUID> dispatchIds = new LinkedHashSet<>();
        BigDecimal dispatched = BigDecimal.ZERO;
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        BigDecimal confirmedDispatched = BigDecimal.ZERO;
        BigDecimal shrinkage = BigDecimal.ZERO;
        int outOfTolerance = 0;
        ReconciliationLine latestConfirmed = null;
        String sku = null;
        String name = null;
        String unit = null;
        for (ReconciliationLine line : group) {
            dispatchIds.add(line.dispatchId());
            dispatched = dispatched.add(line.dispatched());
            received = received.add(line.received() == null ? BigDecimal.ZERO : line.received());
            pending = pending.add(line.pendingQuantity());
            if (line.status() == LineStatus.RECEIVED) {
                confirmedDispatched = confirmedDispatched.add(line.dispatched());
                shrinkage = shrinkage.add(line.shrinkage());
                if (Boolean.FALSE.equals(line.withinTolerance())) {
                    outOfTolerance++;
                }
                if (latestConfirmed == null || isAfter(line.receivedAt(), latestConfirmed.receivedAt())) {
                    latestConfirmed = line;
                }
            }
            sku = sku != null ? sku : line.sku();
            name = name != null ? name : line.materialName();
            unit = unit != null ? unit : line.unit();
        }
        if (material != null) {
            sku = sku != null ? sku : material.sku();
            name = name != null ? name : material.name();
            unit = unit != null ? unit : material.unit();
        }
        BigDecimal tolerance = latestConfirmed != null && latestConfirmed.tolerancePercent() != null
                ? latestConfirmed.tolerancePercent() : material == null ? null : material.tolerancePercent();
        BigDecimal percent = percent(shrinkage, confirmedDispatched);
        ToleranceStatus status = percent == null ? ToleranceStatus.NOT_RECEIVED
                : percent.compareTo(tolerance == null ? BigDecimal.ZERO : tolerance) > 0 ? ToleranceStatus.EXCEEDED
                : ToleranceStatus.WITHIN;
        int open = (int) discrepancies.stream().filter(d -> Objects.equals(d.worksiteId(), key.worksiteId())
                && Objects.equals(d.warehouseId(), key.warehouseId())
                && Objects.equals(d.materialId(), key.materialId())).count();
        return new MaterialRow(key.worksiteId(), name(locationNames, key.worksiteId()), key.warehouseId(),
                name(locationNames, key.warehouseId()), key.materialId(), sku, name, unit, dispatchIds.size(),
                quantity(dispatched), quantity(received), quantity(pending), quantity(confirmedDispatched),
                percent == null ? null : quantity(shrinkage), percent, tolerance == null ? null : quantity(tolerance),
                status, outOfTolerance, open);
    }

    private static List<UnitTotal> totalsByUnit(List<MaterialRow> rows) {
        Map<String, List<MaterialRow>> byUnit = new LinkedHashMap<>();
        rows.forEach(row -> byUnit.computeIfAbsent(row.unit() == null ? NO_UNIT : row.unit(),
                unit -> new ArrayList<>()).add(row));
        List<UnitTotal> totals = new ArrayList<>();
        byUnit.forEach((unit, group) -> {
            BigDecimal confirmed = sum(group, MaterialRow::confirmedDispatched);
            BigDecimal shrinkage = sum(group, MaterialRow::shrinkage);
            BigDecimal percent = percent(shrinkage, confirmed);
            totals.add(new UnitTotal(unit, quantity(sum(group, MaterialRow::dispatched)),
                    quantity(sum(group, MaterialRow::received)), quantity(sum(group, MaterialRow::pendingReception)),
                    percent == null ? null : quantity(shrinkage), percent));
        });
        totals.sort(Comparator.comparing(UnitTotal::unit));
        return totals;
    }

    private static List<PendingCase> pendingCases(List<ReconciliationLine> lines, Set<UUID> pendingDispatches,
            List<DiscrepancyCase> discrepancies, Map<UUID, String> locationNames) {
        List<PendingCase> cases = new ArrayList<>();
        for (UUID dispatchId : pendingDispatches) {
            List<ReconciliationLine> pending = lines.stream().filter(line -> line.dispatchId().equals(dispatchId)
                    && line.status().pendingReception()).toList();
            ReconciliationLine first = pending.get(0);
            boolean partial = pending.stream().anyMatch(line -> line.status() == LineStatus.PARTIALLY_RECEIVED);
            Instant since = pending.stream().map(ReconciliationLine::departedAt).filter(Objects::nonNull)
                    .min(Comparator.naturalOrder()).orElse(null);
            cases.add(new PendingCase(PendingCaseType.RECEPTION_PENDING, dispatchId, first.manifestCode(), null,
                    name(locationNames, first.worksiteId()), name(locationNames, first.warehouseId()), null,
                    partial ? LineStatus.PARTIALLY_RECEIVED.label() : LineStatus.IN_TRANSIT.label(), since,
                    pending.size() == 1 ? "1 material por recibir" : pending.size() + " materiales por recibir"));
        }
        for (DiscrepancyCase discrepancy : discrepancies) {
            String detail = discrepancy.typeLabel() == null ? null : discrepancy.typeLabel()
                    + (discrepancy.quantity() == null ? "" : " " + quantity(discrepancy.quantity()).toPlainString());
            cases.add(new PendingCase(PendingCaseType.OPEN_DISCREPANCY, discrepancy.dispatchId(),
                    discrepancy.manifestCode(), discrepancy.discrepancyId(),
                    name(locationNames, discrepancy.worksiteId()), name(locationNames, discrepancy.warehouseId()),
                    material(discrepancy.sku(), discrepancy.materialName()), discrepancy.statusLabel(),
                    discrepancy.openedAt(), detail));
        }
        cases.sort(Comparator.comparing(PendingCase::since, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(PendingCase::type));
        return cases;
    }

    private static String material(String sku, String name) {
        if (sku == null) {
            return name;
        }
        return name == null ? sku : sku + " " + name;
    }

    private static BigDecimal percent(BigDecimal shrinkage, BigDecimal base) {
        if (base.signum() <= 0) {
            return null;
        }
        return shrinkage.multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<MaterialRow> rows, java.util.function.Function<MaterialRow, BigDecimal> field) {
        return rows.stream().map(field).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Sin ceros sobrantes ni notación científica (100.000 → 100, 2.50 → 2.5). */
    static BigDecimal quantity(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /** Nombre de la obra o almacén; si la copia todavía no lo tiene, el inicio de su id. */
    private static String name(Map<UUID, String> names, UUID locationId) {
        if (locationId == null) {
            return null;
        }
        String name = names.get(locationId);
        return name != null ? name : locationId.toString().substring(0, 8);
    }

    private static boolean isAfter(Instant candidate, Instant current) {
        return candidate != null && (current == null || candidate.isAfter(current));
    }

    private record GroupKey(UUID worksiteId, UUID warehouseId, UUID materialId) {
    }
}
