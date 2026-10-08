package pe.buildshield.audit.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.audit.domain.model.AuditEntry;
import pe.buildshield.audit.domain.model.AuditFilter;
import pe.buildshield.audit.domain.model.AuditLogRepository;
import pe.buildshield.audit.domain.model.AuditPage;
import pe.buildshield.audit.domain.model.UserDirectory;
import pe.buildshield.audit.shared.tenant.TenantContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Consulta del registro de auditoría de la organización del token (QAS09), con el nombre de cada responsable. */
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
                page.items().stream().map(AuditEntry::actorId).filter(Objects::nonNull).distinct().toList());
        return new AuditEventsView(page.items().stream()
                .map(entry -> new AuditEventView(entry, entry.actorId() == null ? null : names.get(entry.actorId())))
                .toList(), page.page(), page.size(), page.total());
    }

    public record AuditEventView(AuditEntry entry, String actorName) {
    }

    public record AuditEventsView(List<AuditEventView> items, int page, int size, long total) {
    }
}
