package pe.buildshield.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.audit.domain.model.AssignmentDirectory;
import pe.buildshield.audit.domain.model.AuditEntry;
import pe.buildshield.audit.domain.model.AuditLogRepository;
import pe.buildshield.audit.domain.model.CustodyEventType;
import pe.buildshield.audit.domain.model.UserDirectory;
import pe.buildshield.audit.shared.error.ResourceNotFoundException;
import pe.buildshield.audit.shared.tenant.TenantContext;
import pe.buildshield.audit.shared.tenant.TenantInfo;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Historial de custodia de un despacho (US39): preparación, transportista, pesajes, salida, incidencias, anulación,
 * recepción y discrepancias, ordenado por fecha, armado desde el registro de solo anexado. Cualquier rol de la
 * organización lo consulta; el encargado de obra solo el de los despachos a sus obras. Lo que no puede ver es 404.
 */
@Service
public class CustodyQueryService {

    static final String SITE_MANAGER = "SITE_MANAGER";

    private final AuditLogRepository auditLog;
    private final UserDirectory users;
    private final AssignmentDirectory assignments;
    private final ObjectMapper json;

    public CustodyQueryService(AuditLogRepository auditLog, UserDirectory users, AssignmentDirectory assignments,
            ObjectMapper json) {
        this.auditLog = auditLog;
        this.users = users;
        this.assignments = assignments;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Custody custody(UUID dispatchId) {
        TenantInfo requester = TenantContext.require();
        UUID organizationId = requester.organizationId();
        List<AuditEntry> entries = auditLog.findByDispatch(organizationId, dispatchId);
        if (entries.isEmpty() || !canSee(requester, dispatchId)) {
            throw new ResourceNotFoundException("DISPATCH_NOT_FOUND", "El despacho " + dispatchId + " no existe");
        }
        Map<UUID, String> names = users.names(organizationId,
                entries.stream().map(AuditEntry::actorId).filter(Objects::nonNull).distinct().toList());
        String manifestCode = null;
        List<CustodyEvent> events = new ArrayList<>();
        for (AuditEntry entry : entries) {
            var type = CustodyEventType.fromEventType(entry.eventType());
            if (type.isEmpty()) {
                continue;
            }
            JsonNode payload = read(entry.payload());
            if (manifestCode == null && payload.hasNonNull("manifestCode")) {
                manifestCode = payload.get("manifestCode").asText();
            }
            events.add(new CustodyEvent(entry.occurredAt(), type.get(), type.get().label(), entry.actorId(),
                    entry.actorId() == null ? null : names.get(entry.actorId()),
                    CustodyDetails.describe(type.get(), payload)));
        }
        if (events.isEmpty()) {
            throw new ResourceNotFoundException("DISPATCH_NOT_FOUND", "El despacho " + dispatchId + " no existe");
        }
        return new Custody(dispatchId, manifestCode, events);
    }

    private boolean canSee(TenantInfo requester, UUID dispatchId) {
        if (!SITE_MANAGER.equals(requester.role())) {
            return true;
        }
        return auditLog.findDispatchWorksite(requester.organizationId(), dispatchId)
                .map(worksite -> assignments.isAssigned(requester.organizationId(), requester.userId(), worksite))
                .orElse(false);
    }

    private JsonNode read(String payload) {
        try {
            return json.readTree(payload);
        } catch (IOException ex) {
            return json.createObjectNode();
        }
    }

    public record Custody(UUID dispatchId, String manifestCode, List<CustodyEvent> events) {
    }

    public record CustodyEvent(Instant at, CustodyEventType type, String label, UUID actorId, String actorName,
            String detail) {
    }
}
