package pe.buildshield.audit.application;

import pe.buildshield.audit.domain.model.AssignmentDirectory;
import pe.buildshield.audit.domain.model.AuditEntry;
import pe.buildshield.audit.domain.model.AuditFilter;
import pe.buildshield.audit.domain.model.AuditLogRepository;
import pe.buildshield.audit.domain.model.AuditPage;
import pe.buildshield.audit.domain.model.UserDirectory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Registro, usuarios y asignaciones en memoria, con el mismo filtro por organización que JDBC. */
class InMemoryAudit implements AuditLogRepository, UserDirectory, AssignmentDirectory {

    final List<AuditEntry> entries = new ArrayList<>();
    final Map<UUID, String[]> users = new HashMap<>();
    final Map<UUID, UUID[]> assignments = new HashMap<>();

    @Override
    public boolean append(AuditEntry entry) {
        if (entries.stream().anyMatch(existing -> existing.eventId().equals(entry.eventId()))) {
            return false;
        }
        entries.add(entry);
        return true;
    }

    @Override
    public AuditPage search(UUID organizationId, AuditFilter filter) {
        List<AuditEntry> found = entries.stream()
                .filter(entry -> entry.organizationId().equals(organizationId))
                .filter(entry -> filter.eventType() == null || filter.eventType().equals(entry.eventType()))
                .filter(entry -> filter.actorId() == null || filter.actorId().equals(entry.actorId()))
                .sorted(Comparator.comparing(AuditEntry::occurredAt).reversed())
                .toList();
        List<AuditEntry> page = found.stream().skip(filter.offset()).limit(filter.size()).toList();
        return new AuditPage(page, filter.page(), filter.size(), found.size());
    }

    @Override
    public List<AuditEntry> findByDispatch(UUID organizationId, UUID dispatchId) {
        return entries.stream()
                .filter(entry -> entry.organizationId().equals(organizationId))
                .filter(entry -> entry.payload().contains(dispatchId.toString()))
                .sorted(Comparator.comparing(AuditEntry::occurredAt))
                .toList();
    }

    @Override
    public Optional<UUID> findDispatchWorksite(UUID organizationId, UUID dispatchId) {
        return findByDispatch(organizationId, dispatchId).stream()
                .map(entry -> entry.payload().replaceAll(".*\"worksiteId\":\"([0-9a-f-]{36})\".*", "$1"))
                .filter(value -> value.length() == 36)
                .map(UUID::fromString)
                .findFirst();
    }

    @Override
    public void upsert(UUID organizationId, UUID userId, String fullName, String email, String role, boolean active) {
        users.put(userId, new String[]{organizationId.toString(), fullName, role, String.valueOf(active)});
    }

    @Override
    public Map<UUID, String> names(UUID organizationId, Collection<UUID> userIds) {
        Map<UUID, String> names = new HashMap<>();
        for (UUID id : userIds) {
            String[] user = users.get(id);
            if (user != null && user[0].equals(organizationId.toString())) {
                names.put(id, user[1]);
            }
        }
        return names;
    }

    @Override
    public void assign(UUID organizationId, UUID assignmentId, UUID userId, UUID locationId, String locationType) {
        assignments.put(assignmentId, new UUID[]{organizationId, userId, locationId});
    }

    @Override
    public void unassign(UUID assignmentId) {
        assignments.remove(assignmentId);
    }

    @Override
    public boolean isAssigned(UUID organizationId, UUID userId, UUID locationId) {
        return assignments.values().stream().anyMatch(a -> a[0].equals(organizationId) && a[1].equals(userId)
                && a[2].equals(locationId));
    }
}
