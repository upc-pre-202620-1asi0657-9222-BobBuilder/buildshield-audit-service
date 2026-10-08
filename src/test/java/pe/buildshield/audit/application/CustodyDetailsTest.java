package pe.buildshield.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pe.buildshield.audit.domain.model.CustodyEventType;

import static org.assertj.core.api.Assertions.assertThat;

class CustodyDetailsTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void describes_each_step_from_the_payload() throws Exception {
        assertThat(describe(CustodyEventType.CARRIER_ASSIGNED, "{\"carrierName\":\"Transportes Lima\",\"vehiclePlate\":\"ABC-123\"}"))
                .isEqualTo("Transportista Transportes Lima, placa ABC-123");
        assertThat(describe(CustodyEventType.DEPARTURE_WEIGHED, "{\"netKg\":1250.50}")).isEqualTo("Peso neto 1250.5 kg");
        assertThat(describe(CustodyEventType.DEPARTURE_WEIGHED, "{\"grossKg\":3000,\"tareKg\":1000}"))
                .isEqualTo("Peso neto 2000 kg");
        assertThat(describe(CustodyEventType.ARRIVAL_WEIGHED, "{\"netKg\":1200,\"differencePercent\":4.00}"))
                .isEqualTo("Peso neto 1200 kg, diferencia 4 %");
        assertThat(describe(CustodyEventType.DEPARTED, "{\"lines\":[{},{}]}")).isEqualTo("2 materiales despachados");
        assertThat(describe(CustodyEventType.PARTIALLY_RECEIVED, "{\"lines\":[{}]}"))
                .isEqualTo("1 material recibido hasta ahora");
        assertThat(describe(CustodyEventType.INCIDENT, "{\"typeLabel\":\"Accidente\",\"observation\":\"Choque leve\"}"))
                .isEqualTo("Accidente: Choque leve");
        assertThat(describe(CustodyEventType.INCIDENT, "{\"type\":\"CUSTOM\"}")).isEqualTo("CUSTOM");
        assertThat(describe(CustodyEventType.CANCELLED, "{\"reason\":\"Pedido duplicado\"}"))
                .isEqualTo("Motivo Pedido duplicado");
        assertThat(describe(CustodyEventType.RECEIVED, "{\"lines\":[{\"withinTolerance\":true}]}"))
                .isEqualTo("1 material dentro de la tolerancia");
        assertThat(describe(CustodyEventType.DISCREPANCY_RESOLVED, "{\"sku\":\"CEM-001\",\"text\":\"Se repone\"}"))
                .isEqualTo("CEM-001: Se repone");
        assertThat(describe(CustodyEventType.PREPARED, "{\"manifestCode\":\"MAN-1\",\"lines\":[{}]}"))
                .isEqualTo("Manifiesto MAN-1, 1 línea");
    }

    @Test
    void without_useful_fields_the_detail_is_empty() throws Exception {
        for (CustodyEventType type : CustodyEventType.values()) {
            assertThat(describe(type, "{\"lines\":[],\"text\":\" \"}")).as(type.name()).isNull();
        }
        assertThat(describe(CustodyEventType.DEPARTURE_WEIGHED, "{\"netKg\":\"n/d\"}")).isEqualTo("Peso neto n/d kg");
    }

    private String describe(CustodyEventType type, String payload) throws Exception {
        JsonNode node = json.readTree(payload);
        return CustodyDetails.describe(type, node);
    }
}
