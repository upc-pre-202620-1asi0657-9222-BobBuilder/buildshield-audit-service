package pe.buildshield.audit.application;

import pe.buildshield.audit.domain.model.reconciliation.DiscrepancyCase;
import pe.buildshield.audit.domain.model.reconciliation.LineStatus;
import pe.buildshield.audit.domain.model.reconciliation.MaterialInfo;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationCriteria;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationLine;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReadModel;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read model de conciliación en memoria, con las mismas reglas de filtro que la consulta JDBC. */
class InMemoryReconciliation implements ReconciliationReadModel {

    record Owned<T>(UUID organizationId, T value) {
    }

    final Map<UUID, Owned<String[]>> locations = new HashMap<>();
    final Map<UUID, Owned<MaterialInfo>> materials = new HashMap<>();
    final Map<UUID, Owned<ReconciliationLine>> lines = new LinkedHashMap<>();
    final Map<UUID, Owned<DiscrepancyCase>> discrepancies = new LinkedHashMap<>();

    @Override
    public void saveLocation(UUID organizationId, UUID locationId, String type, String name, Instant at) {
        locations.put(locationId, new Owned<>(organizationId, new String[]{type, name}));
    }

    @Override
    public void saveMaterial(UUID organizationId, MaterialInfo material, Instant at) {
        materials.put(material.materialId(), new Owned<>(organizationId, material));
    }

    @Override
    public Optional<ReconciliationLine> findLine(UUID organizationId, UUID dispatchLineId) {
        return Optional.ofNullable(lines.get(dispatchLineId)).filter(o -> o.organizationId().equals(organizationId))
                .map(Owned::value);
    }

    @Override
    public List<ReconciliationLine> findLinesOfDispatch(UUID organizationId, UUID dispatchId) {
        return lines.values().stream().filter(o -> o.organizationId().equals(organizationId))
                .map(Owned::value).filter(line -> line.dispatchId().equals(dispatchId)).toList();
    }

    @Override
    public void saveLine(UUID organizationId, ReconciliationLine line, Instant at) {
        lines.put(line.dispatchLineId(), new Owned<>(organizationId, line));
    }

    @Override
    public Optional<DiscrepancyCase> findDiscrepancy(UUID organizationId, UUID discrepancyId) {
        return Optional.ofNullable(discrepancies.get(discrepancyId))
                .filter(o -> o.organizationId().equals(organizationId)).map(Owned::value);
    }

    @Override
    public void saveDiscrepancy(UUID organizationId, DiscrepancyCase discrepancy, Instant at) {
        discrepancies.put(discrepancy.discrepancyId(), new Owned<>(organizationId, discrepancy));
    }

    @Override
    public void clear(UUID organizationId) {
        locations.values().removeIf(o -> o.organizationId().equals(organizationId));
        materials.values().removeIf(o -> o.organizationId().equals(organizationId));
        lines.values().removeIf(o -> o.organizationId().equals(organizationId));
        discrepancies.values().removeIf(o -> o.organizationId().equals(organizationId));
    }

    @Override
    public List<ReconciliationLine> findLines(UUID organizationId, ReconciliationCriteria criteria) {
        return lines.values().stream().filter(o -> o.organizationId().equals(organizationId)).map(Owned::value)
                .filter(line -> line.status() != LineStatus.CANCELLED).filter(criteria::includes)
                .sorted(Comparator.comparing(ReconciliationLine::periodDate)).toList();
    }

    @Override
    public List<DiscrepancyCase> findOpenDiscrepancies(UUID organizationId, ReconciliationCriteria criteria) {
        return discrepancies.values().stream().filter(o -> o.organizationId().equals(organizationId))
                .map(Owned::value).filter(DiscrepancyCase::open)
                .filter(d -> criteria.includesLocations(d.worksiteId(), d.warehouseId()))
                .filter(d -> {
                    List<ReconciliationLine> ofDispatch = findLinesOfDispatch(organizationId, d.dispatchId());
                    if (ofDispatch.isEmpty()) {
                        return criteria.inPeriod(d.openedAt());
                    }
                    return ofDispatch.stream().anyMatch(line -> line.status() != LineStatus.CANCELLED
                            && criteria.inPeriod(line.periodDate()));
                })
                .sorted(Comparator.comparing(DiscrepancyCase::openedAt)).toList();
    }

    @Override
    public Map<UUID, String> locationNames(UUID organizationId, Collection<UUID> locationIds) {
        Map<UUID, String> names = new HashMap<>();
        locationIds.forEach(id -> Optional.ofNullable(locations.get(id))
                .filter(o -> o.organizationId().equals(organizationId)).ifPresent(o -> names.put(id, o.value()[1])));
        return names;
    }

    @Override
    public Map<UUID, MaterialInfo> materials(UUID organizationId, Collection<UUID> materialIds) {
        Map<UUID, MaterialInfo> found = new HashMap<>();
        materialIds.forEach(id -> Optional.ofNullable(materials.get(id))
                .filter(o -> o.organizationId().equals(organizationId)).ifPresent(o -> found.put(id, o.value())));
        return found;
    }
}
