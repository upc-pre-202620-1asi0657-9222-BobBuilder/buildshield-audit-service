package pe.buildshield.audit.support;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Limpieza SOLO de la base efímera de pruebas ({@code test} de Testcontainers o {@code audit_it}). El registro de
 * auditoría rechaza TRUNCATE con su trigger: la limpieza lo desactiva solo en esta sesión con
 * {@code session_replication_role = replica} (requiere un superusuario de la base de pruebas).
 */
public final class TestDatabase {

    public static final List<String> TABLES = List.of("audit_log", "user_projection", "assignment_projection",
            "processed_events", "location_projection", "material_projection", "reconciliation_line",
            "reconciliation_discrepancy");

    private TestDatabase() {
    }

    public static void reset(JdbcTemplate jdbc) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            String database;
            try (var query = connection.createStatement(); var result = query.executeQuery("SELECT current_database()")) {
                result.next();
                database = result.getString(1);
            }
            if (!(database.equals("test") || database.equals("audit_it"))) {
                throw new IllegalStateException("La limpieza requiere una base temporal de pruebas");
            }
            try (var statement = connection.createStatement()) {
                statement.execute("SET session_replication_role = replica");
                try {
                    statement.execute("TRUNCATE TABLE " + String.join(", ", TABLES) + " CASCADE");
                } finally {
                    statement.execute("SET session_replication_role = DEFAULT");
                }
            }
            return null;
        });
    }
}
