package pe.buildshield.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import pe.buildshield.audit.domain.model.CustodyEventType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Texto de detalle de cada paso de la custodia a partir del payload del evento. Tolera campos ausentes o con
 * otros nombres: si no encuentra nada útil, el detalle queda nulo y la web muestra solo la etiqueta.
 */
final class CustodyDetails {

    private static final Map<String, String> INCIDENT_TYPES = Map.of(
            "DELAY", "Retraso",
            "VEHICLE_FAILURE", "Falla del vehículo",
            "DAMAGE", "Daño de la carga",
            "ACCIDENT", "Accidente",
            "ROUTE_CHANGE", "Cambio de ruta",
            "OTHER", "Otra");

    private CustodyDetails() {
    }

    static String describe(CustodyEventType type, JsonNode payload) {
        return switch (type) {
            case PREPARED -> prepared(payload);
            case CARRIER_ASSIGNED -> join(", ", labeled("Transportista", first(payload, "carrierName", "driverName",
                    "carrier")), labeled("placa", first(payload, "vehiclePlate", "plate", "licensePlate")));
            case DEPARTURE_WEIGHED, ARRIVAL_WEIGHED -> weighing(type, payload);
            case DEPARTED -> lines(payload, "material", "despachado", "despachados");
            case INCIDENT -> join(": ", incidentType(payload), first(payload, "observation"));
            case CANCELLED -> labeled("Motivo", first(payload, "reason", "cancellationReason"));
            case RECEPTION_OPENED -> null;
            case PARTIALLY_RECEIVED -> lines(payload, "material", "recibido hasta ahora", "recibidos hasta ahora");
            case RECEIVED -> received(payload);
            case DISCREPANCY_OPENED, DISCREPANCY_RESPONDED, DISCREPANCY_ESCALATED, DISCREPANCY_RESOLVED,
                 DISCREPANCY_ACCEPTED -> join(": ", first(payload, "materialName", "sku"),
                    first(payload, "text", "observation"));
        };
    }

    private static String prepared(JsonNode payload) {
        String manifest = labeled("Manifiesto", first(payload, "manifestCode"));
        String lines = lines(payload, "línea", null, null);
        return join(", ", manifest, lines);
    }

    private static String weighing(CustodyEventType type, JsonNode payload) {
        String net = first(payload, "netKg");
        if (net == null && payload.hasNonNull("grossKg") && payload.hasNonNull("tareKg")) {
            net = payload.get("grossKg").decimalValue().subtract(payload.get("tareKg").decimalValue())
                    .stripTrailingZeros().toPlainString();
        }
        String text = net == null ? null : "Peso neto " + number(net) + " kg";
        if (type == CustodyEventType.ARRIVAL_WEIGHED && payload.hasNonNull("differencePercent")) {
            text = join(", ", text, "diferencia " + number(payload.get("differencePercent").asText()) + " %");
        }
        return text;
    }

    private static String received(JsonNode payload) {
        JsonNode lines = payload.path("lines");
        if (!lines.isArray() || lines.isEmpty()) {
            return null;
        }
        int outOfTolerance = 0;
        for (JsonNode line : lines) {
            if (line.has("withinTolerance") && !line.path("withinTolerance").asBoolean(true)) {
                outOfTolerance++;
            }
        }
        if (outOfTolerance == 0) {
            return lines.size() == 1 ? "1 material dentro de la tolerancia"
                    : lines.size() + " materiales dentro de la tolerancia";
        }
        return outOfTolerance + " de " + lines.size() + " materiales fuera de la tolerancia";
    }

    private static String lines(JsonNode payload, String noun, String singularSuffix, String pluralSuffix) {
        JsonNode lines = payload.path("lines");
        if (!lines.isArray() || lines.isEmpty()) {
            return null;
        }
        boolean one = lines.size() == 1;
        String word = one ? noun : noun.endsWith("l") ? noun + "es" : noun + "s";
        String suffix = one ? singularSuffix : pluralSuffix;
        return lines.size() + " " + word + (suffix == null ? "" : " " + suffix);
    }

    private static String incidentType(JsonNode payload) {
        String label = first(payload, "typeLabel");
        if (label != null) {
            return label;
        }
        String type = first(payload, "type", "incidentType");
        return type == null ? null : INCIDENT_TYPES.getOrDefault(type, type);
    }

    private static String first(JsonNode payload, String... fields) {
        for (String field : fields) {
            if (payload.hasNonNull(field) && !payload.get(field).asText().isBlank()) {
                return payload.get(field).asText();
            }
        }
        return null;
    }

    private static String labeled(String label, String value) {
        return value == null ? null : label + " " + value;
    }

    private static String number(String value) {
        try {
            return new BigDecimal(value).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ex) {
            return value;
        }
    }

    private static String join(String separator, String... parts) {
        List<String> present = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                present.add(part);
            }
        }
        if (present.isEmpty()) {
            return null;
        }
        String text = String.join(separator, present);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
