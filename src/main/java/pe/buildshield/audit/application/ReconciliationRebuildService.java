package pe.buildshield.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.audit.domain.model.AuditLogRepository;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReadModel;
import pe.buildshield.audit.shared.tenant.TenantContext;

import java.io.IOException;
import java.util.UUID;

/**
 * Reconstruye el read model de conciliación de la organización del token desde el registro de solo anexado: lo vacía
 * y vuelve a aplicar, del más antiguo al más reciente, los eventos que lo alimentan. Sirve para los eventos registrados
 * antes de que existiera el reporte y para corregir el read model si cambia la forma de proyectar. Todo en una
 * transacción: si algo falla, queda como estaba.
 */
@Service
public class ReconciliationRebuildService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRebuildService.class);

    private final AuditLogRepository auditLog;
    private final ReconciliationReadModel readModel;
    private final ReconciliationProjector projector;
    private final ObjectMapper json;

    public ReconciliationRebuildService(AuditLogRepository auditLog, ReconciliationReadModel readModel,
            ReconciliationProjector projector, ObjectMapper json) {
        this.auditLog = auditLog;
        this.readModel = readModel;
        this.projector = projector;
        this.json = json;
    }

    /** @return cuántos eventos del registro se volvieron a aplicar */
    @Transactional
    public int rebuild() {
        UUID organizationId = TenantContext.require().organizationId();
        readModel.clear(organizationId);
        int replayed = auditLog.replay(organizationId, ReconciliationProjector.EVENT_TYPES,
                entry -> projector.apply(entry.eventType(), entry.organizationId(), entry.aggregateId(),
                        entry.occurredAt(), read(entry.payload())));
        log.info("Read model de conciliación reconstruido con {} eventos", replayed);
        return replayed;
    }

    private JsonNode read(String payload) {
        try {
            return json.readTree(payload);
        } catch (IOException ex) {
            return json.createObjectNode();
        }
    }
}
