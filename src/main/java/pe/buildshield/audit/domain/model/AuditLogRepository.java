package pe.buildshield.audit.domain.model;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Bitácora de solo anexado: no hay operaciones para cambiar ni borrar entradas. */
public interface AuditLogRepository {

    /**
     * Anexa la acción al final de la cadena de su organización. Las acciones de una misma organización se anexan de
     * una en una; el mismo evento no entra dos veces.
     *
     * @return la entrada guardada, con su id y su huella; vacío si el evento ya estaba registrado
     */
    Optional<AuditEntry> append(NewAuditEntry entry);

    AuditPage search(UUID organizationId, AuditFilter filter);

    /** Eventos de un despacho (los de recepción y discrepancias incluidos), del más antiguo al más reciente. */
    List<AuditEntry> findByDispatch(UUID organizationId, UUID dispatchId);

    /** Obra de destino del despacho según sus eventos, si alguno la trae. */
    Optional<UUID> findDispatchWorksite(UUID organizationId, UUID dispatchId);

    /**
     * Recorre, del más antiguo al más reciente, las entradas de la organización con esas acciones (el reporte de
     * conciliación se arma con ellas).
     *
     * @return cuántas entradas recorrió
     */
    int replay(UUID organizationId, Collection<String> actions, Consumer<AuditEntry> consumer);

    /**
     * Recorre toda la cadena de la organización en el orden en que se anexó, sin cargarla entera en memoria.
     *
     * @return cuántas entradas recorrió
     */
    int forEachInChainOrder(UUID organizationId, Consumer<AuditEntry> consumer);
}
