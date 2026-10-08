package pe.buildshield.audit.domain.model;

import java.util.UUID;

/** Copia local de las asignaciones de encargados a obras y almacenes. */
public interface AssignmentDirectory {

    void assign(UUID organizationId, UUID assignmentId, UUID userId, UUID locationId, String locationType);

    void unassign(UUID assignmentId);

    boolean isAssigned(UUID organizationId, UUID userId, UUID locationId);
}
