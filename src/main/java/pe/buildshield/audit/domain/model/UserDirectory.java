package pe.buildshield.audit.domain.model;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Copia local de los usuarios de iam: nombre para la custodia y estado para los tokens. */
public interface UserDirectory {

    void upsert(UUID organizationId, UUID userId, String fullName, String email, String role, boolean active);

    /** Nombres de los usuarios conocidos de la organización, por id. */
    Map<UUID, String> names(UUID organizationId, Collection<UUID> userIds);
}
