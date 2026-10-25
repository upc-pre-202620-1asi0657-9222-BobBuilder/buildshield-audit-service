package pe.buildshield.audit.infrastructure.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import pe.buildshield.audit.shared.security.RevokedTokenStore;

import java.util.UUID;

/**
 * Revocación de tokens para el filtro JWT del kernel. La lista de tokens cerrados vive en iam; aquí se valida con la
 * copia local de usuarios que el usuario siga activo y con el rol del token (defensa en profundidad). Si la copia aún
 * no tiene al usuario, no se bloquea.
 */
@Component
class UserProjectionTokenGuard implements RevokedTokenStore {

    private final JdbcTemplate jdbc;

    UserProjectionTokenGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isRevoked(String tokenId) {
        return false;
    }

    @Override
    public boolean isUserBlocked(UUID userId, String role) {
        if (userId == null) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM user_projection WHERE user_id = ? AND (NOT active OR role <> ?))",
                Boolean.class, userId, role == null ? "" : role));
    }
}
