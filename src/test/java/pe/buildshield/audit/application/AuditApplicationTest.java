package pe.buildshield.audit.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pe.buildshield.audit.domain.model.AuditFilter;
import pe.buildshield.audit.domain.model.CustodyEventType;
import pe.buildshield.audit.shared.error.ResourceNotFoundException;
import pe.buildshield.audit.shared.messaging.EventEnvelope;
import pe.buildshield.audit.shared.tenant.TenantContext;
import pe.buildshield.audit.shared.tenant.TenantInfo;
import pe.buildshield.audit.support.MutableClock;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditApplicationTest {

    private static final Instant T0 = Instant.parse("2026-11-03T12:00:00Z");

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final InMemoryAudit store = new InMemoryAudit();
    private final MutableClock clock = new MutableClock(T0);
    private final AuditCommandService commands = new AuditCommandService(store, store, store, clock);
    private final AuditQueryService queries = new AuditQueryService(store, store);
    private final CustodyQueryService custody = new CustodyQueryService(store, store, store, json);

    private final UUID organization = UUID.randomUUID();
    private final UUID otherOrganization = UUID.randomUUID();
    private final UUID dispatch = UUID.randomUUID();
    private final UUID worksite = UUID.randomUUID();
    private final UUID rosa = UUID.randomUUID();
    private final UUID luis = UUID.randomUUID();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void every_event_is_recorded_once_including_unknown_types() {
        EventEnvelope unknown = event("SomethingNew", "X", "1", null, T0, payload());

        assertThat(commands.record("buildshield.inventory", unknown)).isTrue();
        assertThat(commands.record("buildshield.inventory", unknown)).isFalse();
        assertThat(store.entries).hasSize(1);
        assertThat(store.entries.get(0).topic()).isEqualTo("buildshield.inventory");
        assertThat(store.entries.get(0).recordedAt()).isEqualTo(T0);

        EventEnvelope withoutPayloadOrDate = new EventEnvelope(UUID.randomUUID(), "Bare", null, organization, null,
                null, null, 1, null);
        assertThat(commands.record("buildshield.iam", withoutPayloadOrDate)).isTrue();
        assertThat(store.entries.get(1).payload()).isEqualTo("{}");
        assertThat(store.entries.get(1).occurredAt()).isEqualTo(T0);
    }

    @Test
    void user_and_assignment_events_keep_the_local_copies_up_to_date() {
        commands.record("buildshield.iam", event("UserCreated", "USER", rosa.toString(), null, T0,
                payload("userId", rosa.toString(), "fullName", "Rosa Quispe", "role", "WAREHOUSE_MANAGER")));
        commands.record("buildshield.iam", event("UserUpdated", "USER", "x", null, T0, payload("userId", "no-uuid")));
        UUID assignment = UUID.randomUUID();
        commands.record("buildshield.organization", event("StaffAssigned", "ASSIGNMENT", assignment.toString(), null,
                T0, payload("assignmentId", assignment.toString(), "userId", luis.toString(), "locationId",
                        worksite.toString(), "locationType", "WORKSITE")));
        commands.record("buildshield.organization", event("StaffAssigned", "ASSIGNMENT", "y", null, T0,
                payload("assignmentId", UUID.randomUUID().toString())));

        assertThat(store.names(organization, List.of(rosa))).containsEntry(rosa, "Rosa Quispe");
        assertThat(store.isAssigned(organization, luis, worksite)).isTrue();

        commands.record("buildshield.organization", event("StaffUnassigned", "ASSIGNMENT", assignment.toString(),
                null, T0, payload("assignmentId", assignment.toString())));
        commands.record("buildshield.organization", event("StaffUnassigned", "ASSIGNMENT", "z", null, T0, payload()));
        assertThat(store.isAssigned(organization, luis, worksite)).isFalse();
        assertThat(store.entries).hasSize(6);
    }

    @Test
    void search_returns_the_organization_events_with_the_actor_name() {
        commands.record("buildshield.iam", event("UserCreated", "USER", rosa.toString(), null, T0,
                payload("userId", rosa.toString(), "fullName", "Rosa Quispe", "role", "WAREHOUSE_MANAGER")));
        commands.record("buildshield.ordering", event("DispatchDeparted", "DISPATCH", dispatch.toString(), rosa,
                T0.plusSeconds(60), payload("dispatchId", dispatch.toString())));
        commands.record("buildshield.ordering", new EventEnvelope(UUID.randomUUID(), "DispatchDeparted", T0,
                otherOrganization, "DISPATCH", "x", null, 1, payload()));

        var page = TenantContext.callAs(user("ADMINISTRATOR", UUID.randomUUID()), () -> queries.search(
                new AuditFilter(null, null, null, null, null, null, null, 0, 10)));

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items().get(0).entry().eventType()).isEqualTo("DispatchDeparted");
        assertThat(page.items().get(0).actorName()).isEqualTo("Rosa Quispe");
        assertThat(page.items().get(1).actorName()).isNull();
    }

    @Test
    void custody_is_the_ordered_timeline_with_labels_details_and_names() {
        recordTimeline();

        var timeline = TenantContext.callAs(user("WAREHOUSE_MANAGER", rosa), () -> custody.custody(dispatch));

        assertThat(timeline.manifestCode()).isEqualTo("MAN-0001");
        assertThat(timeline.events()).extracting(CustodyQueryService.CustodyEvent::type).containsExactly(
                CustodyEventType.PREPARED, CustodyEventType.DEPARTED, CustodyEventType.INCIDENT,
                CustodyEventType.RECEIVED, CustodyEventType.DISCREPANCY_OPENED);
        assertThat(timeline.events().get(0).actorName()).isEqualTo("Rosa Quispe");
        assertThat(timeline.events().get(0).detail()).isEqualTo("Manifiesto MAN-0001, 2 líneas");
        assertThat(timeline.events().get(2).detail()).isEqualTo("Retraso: Tráfico en la Panamericana");
        assertThat(timeline.events().get(3).detail()).isEqualTo("1 de 2 materiales fuera de la tolerancia");
        assertThat(timeline.events().get(4).label()).isEqualTo("Discrepancia abierta");
    }

    @Test
    void site_manager_only_sees_the_custody_of_dispatches_to_assigned_worksites() {
        recordTimeline();
        UUID assignment = UUID.randomUUID();

        assertThatThrownBy(() -> TenantContext.callAs(user("SITE_MANAGER", luis), () -> custody.custody(dispatch)))
                .isInstanceOf(ResourceNotFoundException.class);

        store.assign(organization, assignment, luis, worksite, "WORKSITE");
        assertThat(TenantContext.callAs(user("SITE_MANAGER", luis), () -> custody.custody(dispatch)).events())
                .hasSize(5);
    }

    @Test
    void unknown_dispatch_or_one_without_custody_events_is_not_found() {
        assertThatThrownBy(() -> TenantContext.callAs(user("ADMINISTRATOR", rosa),
                () -> custody.custody(UUID.randomUUID()))).isInstanceOf(ResourceNotFoundException.class);

        UUID onlyStock = UUID.randomUUID();
        commands.record("buildshield.inventory", event("StockDeducted", "STOCK", "s", null, T0,
                payload("dispatchId", onlyStock.toString())));
        assertThatThrownBy(() -> TenantContext.callAs(user("ADMINISTRATOR", rosa), () -> custody.custody(onlyStock)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private void recordTimeline() {
        commands.record("buildshield.iam", event("UserCreated", "USER", rosa.toString(), null, T0,
                payload("userId", rosa.toString(), "fullName", "Rosa Quispe", "role", "WAREHOUSE_MANAGER")));
        ObjectNode prepared = payload("dispatchId", dispatch.toString(), "worksiteId", worksite.toString(),
                "manifestCode", "MAN-0001");
        prepared.putArray("lines").addObject().put("materialId", "a");
        prepared.withArray("lines").addObject().put("materialId", "b");
        commands.record("buildshield.ordering", event("DispatchPrepared", "DISPATCH", dispatch.toString(), rosa, T0,
                prepared));
        commands.record("buildshield.ordering", event("DispatchDeparted", "DISPATCH", dispatch.toString(), rosa,
                T0.plusSeconds(3600), payload("dispatchId", dispatch.toString(), "worksiteId", worksite.toString())));
        commands.record("buildshield.ordering", event("TransitIncidentReported", "DISPATCH", dispatch.toString(), rosa,
                T0.plusSeconds(4000), payload("dispatchId", dispatch.toString(), "type", "DELAY", "observation",
                        "Tráfico en la Panamericana")));
        ObjectNode confirmed = payload("dispatchId", dispatch.toString(), "receptionId", UUID.randomUUID().toString());
        confirmed.putArray("lines").addObject().put("withinTolerance", false);
        confirmed.withArray("lines").addObject().put("withinTolerance", true);
        commands.record("buildshield.ordering", event("ReceptionConfirmed", "RECEPTION", "r", luis,
                T0.plusSeconds(9000), confirmed));
        commands.record("buildshield.inventory", event("StockReceived", "STOCK", "s", null, T0.plusSeconds(9001),
                payload("dispatchId", dispatch.toString())));
        commands.record("buildshield.discrepancy", event("DiscrepancyOpened", "DISCREPANCY", "d", luis,
                T0.plusSeconds(9002), payload("dispatchId", dispatch.toString(), "materialName", "Cemento",
                        "text", "Faltan 3 bolsas")));
    }

    private TenantInfo user(String role, UUID userId) {
        return new TenantInfo(organization, userId, role);
    }

    private EventEnvelope event(String type, String aggregateType, String aggregateId, UUID actor, Instant at,
            ObjectNode payload) {
        return new EventEnvelope(UUID.randomUUID(), type, at, organization, aggregateType, aggregateId, actor, 1,
                payload);
    }

    private static ObjectNode payload(String... pairs) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < pairs.length; i += 2) {
            node.put(pairs[i], pairs[i + 1]);
        }
        return node;
    }
}
