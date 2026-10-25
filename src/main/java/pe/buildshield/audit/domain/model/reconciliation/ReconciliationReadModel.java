package pe.buildshield.audit.domain.model.reconciliation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read model del reporte de conciliación (US47): copias de obras, almacenes y materiales, líneas de despacho y
 * discrepancias. Se arma al consultar, aplicando sobre una copia en memoria los eventos de {@code audit_log}. Toda operación va acotada a
 * la organización.
 */
public interface ReconciliationReadModel {

    void saveLocation(UUID organizationId, UUID locationId, String type, String name, Instant at);

    void saveMaterial(UUID organizationId, MaterialInfo material, Instant at);

    Optional<ReconciliationLine> findLine(UUID organizationId, UUID dispatchLineId);

    List<ReconciliationLine> findLinesOfDispatch(UUID organizationId, UUID dispatchId);

    void saveLine(UUID organizationId, ReconciliationLine line, Instant at);

    Optional<DiscrepancyCase> findDiscrepancy(UUID organizationId, UUID discrepancyId);

    void saveDiscrepancy(UUID organizationId, DiscrepancyCase discrepancy, Instant at);

    /** Líneas no anuladas que cumplen el criterio. */
    List<ReconciliationLine> findLines(UUID organizationId, ReconciliationCriteria criteria);

    /**
     * Discrepancias abiertas de las obras y almacenes del criterio cuyo despacho entra en el periodo; si el despacho
     * no está en el read model, cuenta la fecha de apertura.
     */
    List<DiscrepancyCase> findOpenDiscrepancies(UUID organizationId, ReconciliationCriteria criteria);

    Map<UUID, String> locationNames(UUID organizationId, Collection<UUID> locationIds);

    Map<UUID, MaterialInfo> materials(UUID organizationId, Collection<UUID> materialIds);
}
