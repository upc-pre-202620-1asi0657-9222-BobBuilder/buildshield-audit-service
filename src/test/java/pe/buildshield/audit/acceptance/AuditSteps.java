package pe.buildshield.audit.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import io.cucumber.java.es.Cuando;
import io.cucumber.java.es.Dado;
import io.cucumber.java.es.Entonces;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import pe.buildshield.audit.shared.security.JwtTokenIssuer;
import pe.buildshield.audit.shared.tenant.TenantInfo;
import pe.buildshield.audit.support.TestEvents;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static pe.buildshield.audit.acceptance.ApiRequest.get;
import static pe.buildshield.audit.acceptance.ApiRequest.post;
import static pe.buildshield.audit.support.TestEvents.map;

/**
 * Pasos de US39 (historial de custodia), QAS09 (registro de auditoría), US47 (reporte de conciliación) y US48
 * (exportación del reporte).
 */
public class AuditSteps {

    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(30);
    private static final Instant T0 = Instant.parse("2026-11-03T12:00:00Z");

    @Autowired
    ScenarioSession session;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtTokenIssuer issuer;

    @Autowired
    ObjectMapper json;

    private final UUID organization = UUID.randomUUID();
    private final Map<String, UUID> users = new HashMap<>();
    private final Map<String, String> roles = new HashMap<>();
    private final Map<String, UUID> worksites = new HashMap<>();
    private final Map<String, UUID> dispatches = new HashMap<>();
    private final Map<String, String> sent = new HashMap<>();
    private int expectedEvents;
    private int minutes;

    /** US47: materiales por SKU ({id, nombre, unidad, tolerancia}) y líneas de cada despacho por manifiesto. */
    private final Map<String, Object[]> materials = new HashMap<>();
    private final Map<String, UUID> manifests = new HashMap<>();
    private final Map<String, UUID[]> routes = new HashMap<>();
    private final Map<String, Map<String, UUID[]>> dispatchLines = new HashMap<>();
    private final Map<String, Map<String, BigDecimal>> dispatched = new HashMap<>();
    private final Map<String, Instant> departures = new HashMap<>();
    private ScenarioSession.Download download;

    private TestEvents events() {
        return new TestEvents(json);
    }

    @Dado("que en la organización trabajan {string} como {string}")
    public void userExists(String name, String role) {
        UUID id = UUID.randomUUID();
        users.put(name, id);
        roles.put(name, role(role));
        send("buildshield.iam", id, events().userCreated(organization, id, name, role(role)));
    }

    @Dado("que {string} está asignado a la obra {string}")
    public void assigned(String name, String worksite) {
        UUID assignment = UUID.randomUUID();
        send("buildshield.organization", assignment, events().staffAssigned(organization, assignment, users.get(name),
                worksite(worksite)));
    }

    @Dado("que {string} preparó el despacho {string} con manifiesto {string} hacia la obra {string}")
    public void prepared(String name, String dispatch, String manifest, String worksite) {
        dispatches.put(dispatch, UUID.randomUUID());
        dispatchEvent("DispatchPrepared", name, dispatch, worksite, map("manifestCode", manifest, "lines",
                List.of(map("dispatchLineId", UUID.randomUUID(), "materialId", UUID.randomUUID(), "quantity", 10))));
    }

    @Dado("que {string} registró el evento {string} del despacho {string} hacia la obra {string}")
    public void dispatchStep(String name, String eventType, String dispatch, String worksite) {
        Map<String, Object> extra = switch (eventType) {
            case "TransitIncidentReported" -> map("incidentId", UUID.randomUUID(), "type", "DELAY", "observation",
                    "Tráfico en la Panamericana");
            case "ReceptionConfirmed" -> map("receptionId", UUID.randomUUID(), "lines", List.of(
                    map("materialId", UUID.randomUUID(), "withinTolerance", false)));
            default -> map();
        };
        dispatchEvent(eventType, name, dispatch, worksite, extra);
    }

    @Dado("que {string} abrió una discrepancia del despacho {string} por {string}")
    public void discrepancyOpened(String name, String dispatch, String text) {
        minutes += 10;
        send("buildshield.discrepancy", dispatches.get(dispatch), events().discrepancyEvent("DiscrepancyOpened",
                organization, UUID.randomUUID(), dispatches.get(dispatch), users.get(name), T0.plus(Duration.ofMinutes(minutes)),
                text));
    }

    @Dado("que el evento de salida del despacho {string} llegó dos veces más")
    public void duplicateDeparture(String dispatch) {
        String departed = sent.get("DispatchDeparted");
        kafka.send("buildshield.ordering", dispatches.get(dispatch).toString(), departed);
        kafka.send("buildshield.ordering", dispatches.get(dispatch).toString(), departed);
        // Mismo despacho, misma partición: cuando llega este evento (que no es de custodia) los repetidos ya pasaron.
        send("buildshield.ordering", dispatches.get(dispatch), events().dispatchEvent("OrderClosed", organization,
                dispatches.get(dispatch), worksites.values().iterator().next(), null, T0.plus(Duration.ofDays(1)),
                map("reason", "Pedido completo")));
    }

    @Cuando("{string} consulta el historial de custodia del despacho {string}")
    public void consultCustody(String name, String dispatch) throws Exception {
        awaitEvents();
        signIn(name);
        session.send(get("/api/v1/dispatches/" + dispatches.get(dispatch) + "/custody"), null);
    }

    @Cuando("{string} consulta el registro de auditoría del tipo {string}")
    public void consultAudit(String name, String eventType) throws Exception {
        awaitEvents();
        signIn(name);
        session.send(get("/api/v1/audit/events").param("eventType", eventType), null);
    }

    @Cuando("{string} consulta el registro de auditoría")
    public void consultAll(String name) throws Exception {
        awaitEvents();
        signIn(name);
        session.send(get("/api/v1/audit/events"), null);
    }

    @Entonces("ve el manifiesto {string} y los pasos {string} en ese orden")
    public void stepsAre(String manifest, String labels) throws Exception {
        session.expectStatus(200);
        JsonNode body = session.body();
        assertThat(body.path("manifestCode").asText()).isEqualTo(manifest);
        List<String> found = new ArrayList<>();
        body.path("events").forEach(event -> found.add(event.path("label").asText()));
        assertThat(found).containsExactly(labels.split(",\\s*"));
    }

    @Entonces("el paso {string} lo hizo {string} con el detalle {string}")
    public void stepDetail(String label, String name, String detail) throws Exception {
        for (JsonNode event : session.body().path("events")) {
            if (event.path("label").asText().equals(label)) {
                assertThat(event.path("actorName").asText()).isEqualTo(name);
                assertThat(event.path("detail").asText()).isEqualTo(detail);
                return;
            }
        }
        throw new AssertionError("No está el paso " + label);
    }

    @Entonces("la respuesta tiene estado {int}")
    public void responseStatus(int status) {
        session.expectStatus(status);
    }

    @Entonces("ve {int} evento(s) del tipo {string}")
    public void eventsOfType(int count, String eventType) throws Exception {
        session.expectStatus(200);
        JsonNode body = session.body();
        assertThat(body.path("total").asInt()).isEqualTo(count);
        body.path("items").forEach(item -> assertThat(item.path("eventType").asText()).isEqualTo(eventType));
    }

    @Entonces("ve {int} eventos en total, del más reciente al más antiguo")
    public void allEvents(int count) throws Exception {
        session.expectStatus(200);
        JsonNode items = session.body().path("items");
        assertThat(session.body().path("total").asInt()).isEqualTo(count);
        for (int i = 1; i < items.size(); i++) {
            assertThat(items.get(i - 1).path("occurredAt").asText().compareTo(items.get(i).path("occurredAt").asText()))
                    .isGreaterThanOrEqualTo(0);
        }
    }

    // ---------- US47 y US48: reporte de conciliación ----------

    @Dado("que existen la obra {string}, la obra {string} y el almacén {string}")
    public void locations(String first, String second, String warehouse) {
        location(first, "WORKSITE");
        location(second, "WORKSITE");
        location(warehouse, "WAREHOUSE");
    }

    @Dado("que el material {string} {string} en {string} tiene tolerancia de {int} %")
    public void material(String sku, String name, String unit, int tolerance) {
        UUID id = UUID.randomUUID();
        materials.put(sku, new Object[]{id, name, unit, BigDecimal.valueOf(tolerance)});
        send("buildshield.organization", id, events().envelope(UUID.randomUUID(), "MaterialRegistered",
                organization, "MATERIAL", id, null, T0.minus(Duration.ofDays(30)), map("materialId", id, "sku", sku,
                        "name", name, "unit", unit, "tolerancePercent", tolerance, "active", true)));
    }

    @Dado("que el despacho {string} salió el {string} del {string} a {string} con {string}")
    public void departed(String manifest, String date, String warehouse, String worksite, String items) {
        UUID dispatch = UUID.randomUUID();
        manifests.put(manifest, dispatch);
        routes.put(manifest, new UUID[]{worksite(warehouse), worksite(worksite)});
        Map<String, UUID[]> lines = new LinkedHashMap<>();
        Map<String, BigDecimal> quantities = new LinkedHashMap<>();
        List<Map<String, Object>> payloadLines = new ArrayList<>();
        quantities(items).forEach((sku, quantity) -> {
            UUID line = UUID.randomUUID();
            UUID orderLine = UUID.randomUUID();
            lines.put(sku, new UUID[]{line, orderLine});
            quantities.put(sku, quantity);
            payloadLines.add(map("dispatchLineId", line, "orderLineId", orderLine, "materialId", materialId(sku),
                    "quantity", quantity));
        });
        dispatchLines.put(manifest, lines);
        dispatched.put(manifest, quantities);
        Instant at = Instant.parse(date + "T13:00:00Z");
        departures.put(manifest, at);
        send("buildshield.ordering", dispatch, events().envelope(UUID.randomUUID(), "DispatchDeparted", organization,
                "DISPATCH", dispatch, null, at, route(manifest, map("departedAt", at.toString(), "lines",
                        payloadLines))));
    }

    @Dado("que {string} recibió parcialmente {string} con {string}")
    public void partial(String worksite, String manifest, String items) {
        List<Map<String, Object>> payloadLines = new ArrayList<>();
        quantities(items).forEach((sku, quantity) -> payloadLines.add(map("receptionLineId", UUID.randomUUID(),
                "orderLineId", dispatchLines.get(manifest).get(sku)[1], "materialId", materialId(sku),
                "receivedQuantity", quantity, "deltaQuantity", quantity)));
        UUID dispatch = manifests.get(manifest);
        send("buildshield.ordering", dispatch, events().envelope(UUID.randomUUID(), "ReceptionPartiallyReceived",
                organization, "RECEPTION", UUID.randomUUID(), null, departures.get(manifest).plus(Duration.ofHours(2)),
                route(manifest, map("lines", payloadLines))));
    }

    @Dado("que {string} confirmó la recepción de {string} con {string}")
    public void confirmed(String worksite, String manifest, String items) {
        List<Map<String, Object>> payloadLines = new ArrayList<>();
        quantities(items).forEach((sku, received) -> {
            BigDecimal sent = dispatched.get(manifest).get(sku);
            BigDecimal shrinkage = sent.subtract(received).multiply(BigDecimal.valueOf(100))
                    .divide(sent, 2, RoundingMode.HALF_UP);
            BigDecimal tolerance = (BigDecimal) materials.get(sku)[3];
            UUID[] line = dispatchLines.get(manifest).get(sku);
            payloadLines.add(map("receptionLineId", UUID.randomUUID(), "dispatchLineId", line[0], "orderLineId",
                    line[1], "materialId", materialId(sku), "sku", sku, "materialName", materials.get(sku)[1], "unit",
                    materials.get(sku)[2], "requested", sent, "dispatched", sent, "received", received,
                    "deltaQuantity", received, "shrinkagePercent", shrinkage, "tolerancePercent", tolerance,
                    "withinTolerance", shrinkage.compareTo(tolerance) <= 0));
        });
        UUID dispatch = manifests.get(manifest);
        Instant at = departures.get(manifest).plus(Duration.ofHours(3));
        send("buildshield.ordering", dispatch, events().envelope(UUID.randomUUID(), "ReceptionConfirmed",
                organization, "RECEPTION", UUID.randomUUID(), null, at, route(manifest,
                        map("confirmedAt", at.toString(), "lines", payloadLines))));
    }

    @Dado("que se abrió una discrepancia {string} de {int} de {string} en {string}")
    public void discrepancy(String type, int quantity, String sku, String manifest) {
        UUID id = UUID.randomUUID();
        send("buildshield.discrepancy", id, events().envelope(UUID.randomUUID(), "DiscrepancyOpened", organization,
                "DISCREPANCY", id, null, departures.get(manifest).plus(Duration.ofHours(4)), route(manifest, map("discrepancyId", id,
                        "materialId", materialId(sku), "sku", sku, "materialName", materials.get(sku)[1], "type", type,
                        "quantity", quantity, "status", "OPEN", "text", "Faltan bolsas"))));
    }

    @Dado("que el read model de conciliación se borró")
    public void readModelCleared() {
        awaitEvents();
        jdbc.update("DELETE FROM reconciliation_line WHERE organization_id = ?", organization);
        jdbc.update("DELETE FROM reconciliation_discrepancy WHERE organization_id = ?", organization);
    }

    @Cuando("{string} consulta la conciliación de {string} del {string} al {string}")
    public void reportOf(String name, String worksite, String from, String to) throws Exception {
        awaitEvents();
        signIn(name);
        session.send(get("/api/v1/audit/reports/reconciliation").param("worksiteId", worksite(worksite).toString())
                .param("from", from).param("to", to), null);
    }

    @Cuando("{string} consulta la conciliación del {string} al {string}")
    public void report(String name, String from, String to) throws Exception {
        awaitEvents();
        signIn(name);
        session.send(get("/api/v1/audit/reports/reconciliation").param("from", from).param("to", to), null);
    }

    @Cuando("{string} reconstruye el read model de conciliación")
    public void rebuild(String name) throws Exception {
        awaitEvents();
        signIn(name);
        session.send(post("/api/v1/audit/reports/reconciliation/rebuild"), null);
    }

    @Cuando("{string} exporta la conciliación de {string} del {string} al {string} en {string}")
    public void export(String name, String worksite, String from, String to, String format) throws Exception {
        awaitEvents();
        signIn(name);
        download = session.download(get("/api/v1/audit/reports/reconciliation/export").param("format", format)
                .param("worksiteId", worksite(worksite).toString()).param("from", from).param("to", to));
        if (download.status() == 200 && "ADMINISTRATOR".equals(roles.get(name))) {
            // Ejemplo real para el informe: target/report-samples/reporte-conciliacion-ejemplo.csv y .pdf
            Path samples = Path.of("target", "report-samples");
            Files.createDirectories(samples);
            Files.write(samples.resolve("reporte-conciliacion-ejemplo." + format), download.content());
        }
    }

    @Entonces("la fila de {string} en {string} muestra despachado {string}, recibido {string}, por recibir {string}, merma {string} y merma {string} %")
    public void rowShows(String sku, String worksite, String sent, String received, String pending,
            String shrinkage, String percent) throws Exception {
        JsonNode row = row(sku, worksite);
        assertThat(row.path("dispatched").decimalValue()).isEqualByComparingTo(sent);
        assertThat(row.path("received").decimalValue()).isEqualByComparingTo(received);
        assertThat(row.path("pendingReception").decimalValue()).isEqualByComparingTo(pending);
        assertThat(row.path("shrinkage").decimalValue()).isEqualByComparingTo(shrinkage);
        assertThat(row.path("shrinkagePercent").decimalValue()).isEqualByComparingTo(percent);
    }

    @Entonces("la fila de {string} en {string} {string} la tolerancia")
    public void rowTolerance(String sku, String worksite, String verdict) throws Exception {
        assertThat(row(sku, worksite).path("toleranceStatus").asText())
                .isEqualTo(verdict.equals("supera") ? "EXCEEDED" : "WITHIN");
    }

    @Entonces("hay {int} casos pendientes: {string}")
    public void pendingCases(int count, String types) throws Exception {
        session.expectStatus(200);
        List<String> found = new ArrayList<>();
        session.body().path("pendingCases").forEach(item -> found.add(switch (item.path("type").asText()) {
            case "OPEN_DISCREPANCY" -> "Discrepancia abierta";
            case "RECEPTION_PENDING" -> "Recepción pendiente";
            default -> item.path("type").asText();
        }));
        assertThat(found).hasSize(count).containsExactly(types.split(",\\s*"));
    }

    @Entonces("el reporte tiene {int} despacho(s) y solo de {string}")
    public void onlyWorksite(int dispatches, String worksite) throws Exception {
        session.expectStatus(200);
        JsonNode body = session.body();
        assertThat(body.path("summary").path("dispatchCount").asInt()).isEqualTo(dispatches);
        body.path("rows").forEach(row -> assertThat(row.path("worksiteName").asText()).isEqualTo(worksite));
    }

    @Entonces("se volvieron a aplicar {int} eventos")
    public void replayed(int count) throws Exception {
        session.expectStatus(200);
        assertThat(session.body().path("eventsReplayed").asInt()).isEqualTo(count);
    }

    @Entonces("descarga un archivo {string} con extensión {string}")
    public void downloaded(String contentType, String extension) {
        assertThat(download.status()).as(new String(download.content(), StandardCharsets.UTF_8)).isEqualTo(200);
        assertThat(download.contentType()).startsWith(contentType);
        assertThat(download.contentDisposition())
                .matches("attachment; filename=\"conciliacion-\\d{8}-\\d{4}\\." + extension + "\".*");
    }

    @Entonces("el CSV empieza con BOM y tiene las líneas {string}")
    public void csvLines(String expected) {
        byte[] bytes = download.content();
        assertThat(Arrays.copyOf(bytes, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        List<String> lines = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8).lines().toList();
        for (String line : expected.split("\\s*\\|\\s*")) {
            assertThat(lines).anyMatch(found -> found.startsWith(line));
        }
    }

    @Entonces("el PDF dice {string}")
    public void pdfSays(String expected) throws Exception {
        String text = new PdfTextExtractor(new PdfReader(download.content())).getTextFromPage(1);
        for (String part : expected.split("\\s*\\|\\s*")) {
            assertThat(text).contains(part);
        }
    }

    private JsonNode row(String sku, String worksite) throws Exception {
        session.expectStatus(200);
        for (JsonNode row : session.body().path("rows")) {
            if (row.path("sku").asText().equals(sku) && row.path("worksiteName").asText().equals(worksite)) {
                return row;
            }
        }
        throw new AssertionError("No está la fila de " + sku + " en " + worksite + ": " + session.last().body());
    }

    private void location(String name, String type) {
        UUID id = worksite(name);
        send("buildshield.organization", id, events().envelope(UUID.randomUUID(), "LocationRegistered", organization,
                "LOCATION", id, null, T0.minus(Duration.ofDays(30)), map("locationId", id, "type", type, "name", name,
                        "address", "Av. Principal 123", "active", true)));
    }

    private Map<String, Object> route(String manifest, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dispatchId", manifests.get(manifest));
        payload.put("manifestCode", manifest);
        payload.put("warehouseId", routes.get(manifest)[0]);
        payload.put("worksiteId", routes.get(manifest)[1]);
        payload.putAll(extra);
        return payload;
    }

    private UUID materialId(String sku) {
        return (UUID) materials.get(sku)[0];
    }

    /** "50 CEM-001, 100 FIE-012" da {CEM-001=50, FIE-012=100}. */
    private static Map<String, BigDecimal> quantities(String items) {
        Map<String, BigDecimal> quantities = new LinkedHashMap<>();
        for (String item : items.split("\\s*,\\s*")) {
            String[] parts = item.trim().split("\\s+");
            quantities.put(parts[1], new BigDecimal(parts[0]));
        }
        return quantities;
    }

    private void dispatchEvent(String eventType, String name, String dispatch, String worksite,
            Map<String, Object> extra) {
        minutes += 10;
        String message = events().dispatchEvent(eventType, organization, dispatches.get(dispatch), worksite(worksite),
                name == null ? null : users.get(name), T0.plus(Duration.ofMinutes(minutes)), extra);
        sent.put(eventType, message);
        send("buildshield.ordering", dispatches.get(dispatch), message);
    }

    private void send(String topic, UUID key, String message) {
        kafka.send(topic, key.toString(), message);
        expectedEvents++;
    }

    private void awaitEvents() {
        await().atMost(EVENT_TIMEOUT).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE organization_id = ?", Long.class, organization) >= expectedEvents);
    }

    private void signIn(String name) {
        session.accessToken(issuer.issue(new TenantInfo(organization, users.get(name), roles.get(name))).value());
    }

    private UUID worksite(String name) {
        return worksites.computeIfAbsent(name, ignored -> UUID.randomUUID());
    }

    private static String role(String displayName) {
        return switch (displayName.trim().toLowerCase()) {
            case "administrador" -> "ADMINISTRATOR";
            case "encargado de obra" -> "SITE_MANAGER";
            case "encargado de almacén", "encargado de almacen" -> "WAREHOUSE_MANAGER";
            default -> displayName;
        };
    }
}
