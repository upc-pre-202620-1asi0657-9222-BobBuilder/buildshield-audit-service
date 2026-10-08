package pe.buildshield.audit.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.es.Cuando;
import io.cucumber.java.es.Dado;
import io.cucumber.java.es.Entonces;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import pe.buildshield.audit.shared.security.JwtTokenIssuer;
import pe.buildshield.audit.shared.tenant.TenantInfo;
import pe.buildshield.audit.support.TestEvents;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static pe.buildshield.audit.acceptance.ApiRequest.get;
import static pe.buildshield.audit.support.TestEvents.map;

/** Pasos de US39 (historial de custodia) y QAS09 (registro de auditoría). */
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
