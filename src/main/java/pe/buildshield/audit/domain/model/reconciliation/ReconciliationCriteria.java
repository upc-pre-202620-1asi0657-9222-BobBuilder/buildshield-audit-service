package pe.buildshield.audit.domain.model.reconciliation;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Qué líneas y discrepancias entran al reporte. Un conjunto nulo no filtra; uno vacío no deja pasar nada.
 *
 * @param from desde (incluido); nulo, sin límite
 * @param to   hasta (excluido); nulo, sin límite
 */
public record ReconciliationCriteria(Set<UUID> worksites, Set<UUID> warehouses, Instant from, Instant to) {

    public ReconciliationCriteria {
        worksites = worksites == null ? null : Set.copyOf(worksites);
        warehouses = warehouses == null ? null : Set.copyOf(warehouses);
    }

    /** Algún conjunto quedó vacío: el usuario no tiene obras o almacenes a su cargo que coincidan. */
    public boolean matchesNothing() {
        return (worksites != null && worksites.isEmpty()) || (warehouses != null && warehouses.isEmpty());
    }

    public boolean includes(ReconciliationLine line) {
        return includesLocations(line.worksiteId(), line.warehouseId()) && inPeriod(line.periodDate());
    }

    public boolean includesLocations(UUID worksiteId, UUID warehouseId) {
        return in(worksites, worksiteId) && in(warehouses, warehouseId);
    }

    public boolean inPeriod(Instant at) {
        if (at == null) {
            return from == null && to == null;
        }
        return (from == null || !at.isBefore(from)) && (to == null || at.isBefore(to));
    }

    private static boolean in(Set<UUID> allowed, UUID value) {
        return allowed == null || (value != null && allowed.contains(value));
    }
}
