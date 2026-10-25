package pe.buildshield.audit.domain.model;

import java.util.List;

/** Página del registro, del más reciente al más antiguo. */
public record AuditPage(List<AuditEntry> items, int page, int size, long total) {
}
