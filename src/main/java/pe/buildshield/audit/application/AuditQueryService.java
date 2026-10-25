package pe.buildshield.audit.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.audit.domain.model.AuditEntry;
import pe.buildshield.audit.domain.model.AuditFilter;
import pe.buildshield.audit.domain.model.AuditLogRepository;
import pe.buildshield.audit.domain.model.AuditPage;
import pe.buildshield.audit.domain.model.ChainVerification;
import pe.buildshield.audit.domain.model.UserDirectory;
import pe.buildshield.audit.shared.tenant.TenantContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Consulta de la bitácora de auditoría de la organización del token (QAS09), con el nombre de cada responsable, y
 * verificación de su cadena de huellas SHA-256 (RNF15: 0 eventos alterados).
 */
@Service
public class AuditQueryService {

    private final AuditLogRepository auditLog;
    private final UserDirectory users;

    public AuditQueryService(AuditLogRepository auditLog, UserDirectory users) {
        this.auditLog = auditLog;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public AuditEventsView search(AuditFilter filter) {
        UUID organizationId = TenantContext.require().organizationId();
        AuditPage page = auditLog.search(organizationId, filter);
        Map<UUID, String> names = users.names(organizationId,
                page.items().stream().map(AuditEntry::actorUserId).filter(Objects::nonNull).distinct().toList());
        return new AuditEventsView(page.items().stream()
                .map(entry -> new AuditEventView(entry, names.get(entry.actorUserId()))).toList(), page.page(),
                page.size(), page.total());
    }

    /** Recorre toda la cadena de la organización y comprueba cada huella y cada enlace con la entrada anterior. */
    @Transactional(readOnly = true)
    public ChainVerification verify() {
        ChainVerification.Verifier verifier = new ChainVerification.Verifier();
        auditLog.forEachInChainOrder(TenantContext.require().organizationId(), verifier::accept);
        return verifier.result();
    }

    public record AuditEventView(AuditEntry entry, String actorName) {
    }

    public record AuditEventsView(List<AuditEventView> items, int page, int size, long total) {
    }
}
