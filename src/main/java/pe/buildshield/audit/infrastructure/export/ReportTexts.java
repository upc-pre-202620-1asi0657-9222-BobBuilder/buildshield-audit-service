package pe.buildshield.audit.infrastructure.export;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Textos y formatos comunes de las exportaciones (fechas en formato peruano dd/MM/yyyy). */
final class ReportTexts {

    static final String TITLE = "BuildShield";
    static final String SUBTITLE = "Reporte de conciliación por obra y periodo";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private ReportTexts() {
    }

    static String date(LocalDate date) {
        return date == null ? "Sin límite" : DATE.format(date);
    }

    static String dateTime(Instant instant, ZoneId zone) {
        return DATE_TIME.format(instant.atZone(zone));
    }

    /** Nombre del filtro; sin filtro, «Todas» o «Todos»; filtrado pero sin nombre conocido, el inicio del id. */
    static String orAll(String name, UUID id, String all) {
        if (id == null) {
            return all;
        }
        return name != null ? name : id.toString().substring(0, 8);
    }
}
