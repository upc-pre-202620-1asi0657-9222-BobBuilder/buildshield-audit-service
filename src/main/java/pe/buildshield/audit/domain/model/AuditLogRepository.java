package pe.buildshield.audit.domain.model;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Registro de solo anexado: no hay operaciones para cambiar ni borrar entradas. */
public interface AuditLogRepository {

    /** @return {@code false} si el evento ya estaba registrado (mismo eventId) */
    boolean append(AuditEntry entry);

    AuditPage search(UUID organizationId, AuditFilter filter);

    /** Eventos de un despacho (los de recepción y discrepancias incluidos), del más antiguo al más reciente. */
    List<AuditEntry> findByDispatch(UUID organizationId, UUID dispatchId);

    /** Obra de destino del despacho según sus eventos, si alguno la trae. */
    Optional<UUID> findDispatchWorksite(UUID organizationId, UUID dispatchId);

    /**
     * Recorre, del más antiguo al más reciente, los eventos de la organización de esos tipos (reconstrucción de los
     * read models).
     *
     * @return cuántos eventos recorrió
     */
    int replay(UUID organizationId, Collection<String> eventTypes, Consumer<AuditEntry> action);
}
