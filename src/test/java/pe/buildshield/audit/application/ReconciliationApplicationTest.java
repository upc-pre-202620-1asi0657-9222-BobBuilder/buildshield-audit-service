package pe.buildshield.audit.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.buildshield.audit.application.ReconciliationExportService.ExportedFile;
import pe.buildshield.audit.application.ReconciliationQueryService.ReconciliationFilter;
import pe.buildshield.audit.domain.model.reconciliation.LineStatus;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport.MaterialRow;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport.PendingCaseType;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport.ToleranceStatus;
import pe.buildshield.audit.shared.error.ForbiddenException;
import pe.buildshield.audit.shared.error.ValidationException;
import pe.buildshield.audit.shared.messaging.EventEnvelope;
import pe.buildshield.audit.shared.tenant.TenantContext;
import pe.buildshield.audit.shared.tenant.TenantInfo;
import pe.buildshield.audit.support.MutableClock;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US47 y US48 en la capa de aplicación: proyección de los eventos, permisos por rol, periodo, reconstrucción desde el
 * registro y exportación.
 */
class ReconciliationApplicationTest {

    private static final Instant NOW = Instant.parse("2026-12-01T14:05:00Z");
    private static final LocalDate NOV_1 = LocalDate.parse("2026-11-01");
    private static final LocalDate NOV_30 = LocalDate.parse("2026-11-30");

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final InMemoryAudit store = new InMemoryAudit();
    /** Proyección directa de los mismos eventos, para comprobar qué deja cada uno en el read model. */
    private final InMemoryReconciliationReadModel readModel = new InMemoryReconciliationReadModel();
    private final MutableClock clock = new MutableClock(NOW);
    private final ReconciliationProjector projector = new ReconciliationProjector(readModel);
    private final AuditRecorder commands = new AuditRecorder(store, store, store, clock);
    private final ReconciliationQueryService queries = new ReconciliationQueryService(store, json, store, store, clock,
            "America/Lima");
    private final ReconciliationExportService exports = new ReconciliationExportService(queries,
            List.of(new FakeRenderer()));

    private final UUID organization = UUID.randomUUID();
    private final UUID ana = UUID.randomUUID();
    private final UUID rosa = UUID.randomUUID();
    private final UUID luis = UUID.randomUUID();
    private final UUID pedro = UUID.randomUUID();
    private final UUID torreNorte = UUID.randomUUID();
    private final UUID edificioSur = UUID.randomUUID();
    private final UUID almacen = UUID.randomUUID();
    private final UUID cemento = UUID.randomUUID();
    private final UUID fierro = UUID.randomUUID();
    private final UUID d1 = UUID.randomUUID();
    private final UUID d2 = UUID.randomUUID();
    private final UUID d3 = UUID.randomUUID();
    private final UUID d4 = UUID.randomUUID();
    private final UUID l1 = UUID.randomUUID();
    private final UUID l2 = UUID.randomUUID();
    private final UUID ol1 = UUID.randomUUID();
    private final UUID ol2 = UUID.randomUUID();
    private final UUID discrepancyD1 = UUID.randomUUID();
    private final UUID discrepancySur = UUID.randomUUID();

    @BeforeEach
    void history() {
        record("buildshield.iam", "UserCreated", null, at("2026-10-01T10:00:00Z"), obj("userId", ana.toString(),
                "fullName", "Ana Torres", "role", "ADMINISTRATOR"));
        assign(rosa, almacen, "WAREHOUSE");
        assign(luis, torreNorte, "WORKSITE");
        assign(pedro, edificioSur, "WORKSITE");
        location(torreNorte, "WORKSITE", "Torre Norte");
        location(edificioSur, "WORKSITE", "Edificio Sur");
        location(almacen, "WAREHOUSE", "Almacén Central");
        material(cemento, "CEM-001", "Cemento Portland", "BAG", "2");
        material(fierro, "FIE-012", "Fierro corrugado 1/2", "KG", "1");

        departed(d1, torreNorte, "MAN-1", "2026-11-03T13:00:00Z", line(l1, ol1, cemento, "50"),
                line(l2, ol2, fierro, "100"));
        ObjectNode partial = dispatchPayload(d1, torreNorte, "MAN-1");
        partial.putArray("lines").add(obj("orderLineId", ol1.toString(), "receivedQuantity", "20"));
        record("buildshield.ordering", "ReceptionPartiallyReceived", d1, at("2026-11-03T15:00:00Z"), partial);
        ObjectNode confirmed = dispatchPayload(d1, torreNorte, "MAN-1");
        confirmed.put("confirmedAt", "2026-11-03T16:00:00Z");
        confirmed.putArray("lines")
                .add(confirmedLine(l1, ol1, cemento, "CEM-001", "Cemento Portland", "BAG", "50", "47", "6.00", "2",
                        false))
                .add(confirmedLine(l2, ol2, fierro, "FIE-012", "Fierro corrugado 1/2", "KG", "100", "100", "0.00",
                        "1", true));
        record("buildshield.ordering", "ReceptionConfirmed", d1, at("2026-11-03T16:00:00Z"), confirmed);
        discrepancy("DiscrepancyOpened", discrepancyD1, d1, torreNorte, cemento, "OPEN", "2026-11-03T17:00:00Z");
        UUID resolved = UUID.randomUUID();
        discrepancy("DiscrepancyOpened", resolved, d1, torreNorte, fierro, "OPEN", "2026-11-03T17:01:00Z");
        discrepancy("DiscrepancyResolved", resolved, d1, torreNorte, fierro, "RESOLVED", "2026-11-04T10:00:00Z");

        departed(d2, torreNorte, "MAN-2", "2026-11-10T13:00:00Z", line(UUID.randomUUID(), UUID.randomUUID(),
                cemento, "50"));
        departed(d3, edificioSur, "MAN-3", "2026-11-12T13:00:00Z", line(UUID.randomUUID(), UUID.randomUUID(),
                cemento, "30"));
        record("buildshield.ordering", "DispatchCancelled", d3, at("2026-11-12T14:00:00Z"),
                obj("dispatchId", d3.toString(), "reason", "Camión averiado"));
        departed(d4, edificioSur, "MAN-4", "2026-12-02T13:00:00Z", line(UUID.randomUUID(), UUID.randomUUID(),
                fierro, "10"));
        UUID unknownDispatch = UUID.randomUUID();
        discrepancy("DiscrepancyOpened", discrepancySur, unknownDispatch, edificioSur, cemento, "OPEN",
                "2026-11-20T09:00:00Z");
        discrepancy("DiscrepancyResponded", discrepancySur, unknownDispatch, edificioSur, cemento, "RESPONDED",
                "2026-11-21T09:00:00Z");
        // Otra organización: no aparece en nada.
        commands.record(new EventEnvelope(UUID.randomUUID(), "DispatchDeparted",
                at("2026-11-05T10:00:00Z"), UUID.randomUUID(), "DISPATCH", "x", null, 1,
                departurePayload(UUID.randomUUID(), torreNorte, "MAN-X", "2026-11-05T10:00:00Z",
                        line(UUID.randomUUID(), UUID.randomUUID(), cemento, "5"))));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void administrator_sees_dispatched_received_shrinkage_and_pending_cases_of_the_period() {
        ReconciliationReport report = as(ana, "ADMINISTRATOR", new ReconciliationFilter(null, null, NOV_1, NOV_30));

        assertThat(report.generatedAt()).isEqualTo(NOW);
        assertThat(report.generatedBy()).isEqualTo("Ana Torres");
        assertThat(report.rows()).hasSize(2);
        MaterialRow cement = report.rows().get(0);
        assertThat(cement.worksiteName()).isEqualTo("Torre Norte");
        assertThat(cement.warehouseName()).isEqualTo("Almacén Central");
        assertThat(cement.sku()).isEqualTo("CEM-001");
        assertThat(cement.dispatchCount()).isEqualTo(2);
        assertThat(cement.dispatched()).isEqualByComparingTo("100");
        assertThat(cement.received()).isEqualByComparingTo("47");
        assertThat(cement.pendingReception()).isEqualByComparingTo("50");
        assertThat(cement.confirmedDispatched()).isEqualByComparingTo("50");
        assertThat(cement.shrinkage()).isEqualByComparingTo("3");
        assertThat(cement.shrinkagePercent()).isEqualByComparingTo("6.00");
        assertThat(cement.tolerancePercent()).isEqualByComparingTo("2");
        assertThat(cement.toleranceStatus()).isEqualTo(ToleranceStatus.EXCEEDED);
        assertThat(cement.linesOutOfTolerance()).isEqualTo(1);
        assertThat(cement.openDiscrepancies()).isEqualTo(1);
        MaterialRow steel = report.rows().get(1);
        assertThat(steel.unit()).isEqualTo("KG");
        assertThat(steel.toleranceStatus()).isEqualTo(ToleranceStatus.WITHIN);
        assertThat(steel.shrinkagePercent()).isEqualByComparingTo("0");

        assertThat(report.totalsByUnit()).extracting(ReconciliationReport.UnitTotal::unit).containsExactly("BAG", "KG");
        assertThat(report.totalsByUnit().get(0).shrinkagePercent()).isEqualByComparingTo("6.00");
        assertThat(report.summary()).isEqualTo(new ReconciliationReport.Summary(2, 1, 2, 1, 1, 2));
        assertThat(report.pendingCases()).extracting(ReconciliationReport.PendingCase::type).containsExactly(
                PendingCaseType.OPEN_DISCREPANCY, PendingCaseType.RECEPTION_PENDING, PendingCaseType.OPEN_DISCREPANCY);
        assertThat(report.pendingCases().get(0).detail()).isEqualTo("Faltante 3");
        assertThat(report.pendingCases().get(0).material()).isEqualTo("CEM-001 Cemento Portland");
        assertThat(report.pendingCases().get(1).manifestCode()).isEqualTo("MAN-2");
        assertThat(report.pendingCases().get(1).detail()).isEqualTo("1 material por recibir");
        assertThat(report.pendingCases().get(2).status()).isEqualTo("Respondida");
        assertThat(report.pendingCases().get(2).worksiteName()).isEqualTo("Edificio Sur");
    }

    @Test
    void the_period_uses_lima_days_with_both_ends_included_and_no_limits_by_default() {
        ReconciliationReport onlyDecember = as(ana, "ADMINISTRATOR", new ReconciliationFilter(null, null,
                LocalDate.parse("2026-12-02"), LocalDate.parse("2026-12-02")));
        assertThat(onlyDecember.rows()).singleElement().satisfies(row -> {
            assertThat(row.worksiteName()).isEqualTo("Edificio Sur");
            assertThat(row.toleranceStatus()).isEqualTo(ToleranceStatus.NOT_RECEIVED);
            assertThat(row.shrinkage()).isNull();
        });
        // 2026-11-03T13:00Z son las 08:00 del 3 de noviembre en Lima.
        assertThat(as(ana, "ADMINISTRATOR", new ReconciliationFilter(null, null, LocalDate.parse("2026-11-04"),
                LocalDate.parse("2026-11-09"))).summary().dispatchCount()).isZero();
        ReconciliationReport all = as(ana, "ADMINISTRATOR", new ReconciliationFilter(null, null, null, null));
        assertThat(all.summary().dispatchCount()).isEqualTo(3);
        assertThat(all.filters().from()).isNull();
    }

    @Test
    void filters_carry_the_names_of_the_chosen_worksite_and_warehouse() {
        ReconciliationReport report = as(ana, "ADMINISTRATOR", new ReconciliationFilter(torreNorte, almacen, NOV_1,
                NOV_30));

        assertThat(report.filters().worksiteName()).isEqualTo("Torre Norte");
        assertThat(report.filters().warehouseName()).isEqualTo("Almacén Central");
        assertThat(report.summary().openDiscrepancies()).isEqualTo(1);
    }

    @Test
    void site_managers_only_see_their_worksites_and_cannot_ask_for_others() {
        ReconciliationReport luisReport = as(luis, "SITE_MANAGER", new ReconciliationFilter(null, null, NOV_1,
                NOV_30));
        assertThat(luisReport.rows()).allSatisfy(row -> assertThat(row.worksiteId()).isEqualTo(torreNorte));
        assertThat(luisReport.summary().openDiscrepancies()).isEqualTo(1);
        assertThat(luisReport.generatedBy()).isNull();

        ReconciliationReport pedroReport = as(pedro, "SITE_MANAGER", new ReconciliationFilter(edificioSur, null,
                NOV_1, NOV_30));
        assertThat(pedroReport.rows()).isEmpty();
        assertThat(pedroReport.pendingCases()).singleElement()
                .satisfies(pending -> assertThat(pending.discrepancyId()).isEqualTo(discrepancySur));

        assertThatThrownBy(() -> as(luis, "SITE_MANAGER", new ReconciliationFilter(edificioSur, null, null, null)))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("code", "REPORT_SCOPE_FORBIDDEN");
    }

    @Test
    void warehouse_managers_only_see_dispatches_from_their_warehouses() {
        ReconciliationReport rosaReport = as(rosa, "WAREHOUSE_MANAGER", new ReconciliationFilter(null, null, NOV_1,
                NOV_30));
        assertThat(rosaReport.summary().dispatchCount()).isEqualTo(2);

        assertThatThrownBy(() -> as(rosa, "WAREHOUSE_MANAGER", new ReconciliationFilter(null, UUID.randomUUID(),
                null, null))).isInstanceOf(ForbiddenException.class);

        UUID newcomer = UUID.randomUUID();
        ReconciliationReport empty = as(newcomer, "WAREHOUSE_MANAGER", new ReconciliationFilter(null, null, null,
                null));
        assertThat(empty.rows()).isEmpty();
        assertThat(empty.pendingCases()).isEmpty();
        assertThat(empty.summary().dispatchCount()).isZero();
    }

    @Test
    void invalid_period_and_unknown_roles_are_rejected() {
        assertThatThrownBy(() -> as(ana, "ADMINISTRATOR", new ReconciliationFilter(null, null, NOV_30, NOV_1)))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "INVALID_PERIOD");
        assertThatThrownBy(() -> as(ana, "AUDITOR", new ReconciliationFilter(null, null, null, null)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void the_report_is_built_from_the_append_only_log_without_any_read_model_tables() {
        ReconciliationFilter november = new ReconciliationFilter(null, null, NOV_1, NOV_30);
        int projectedLines = readModel.lines.size();
        assertThat(projectedLines).isGreaterThan(0);

        ReconciliationReport first = as(ana, "ADMINISTRATOR", november);

        assertThat(first.rows()).isNotEmpty();
        assertThat(as(ana, "ADMINISTRATOR", november).rows()).isEqualTo(first.rows());
        assertThat(store.entries).anySatisfy(entry -> assertThat(entry.action()).isEqualTo("DispatchDeparted"));
    }

    @Test
    void export_uses_the_same_report_and_names_the_file_with_the_lima_time() {
        ExportedFile file = TenantContext.callAs(new TenantInfo(organization, ana, "ADMINISTRATOR"),
                () -> exports.export(new ReconciliationFilter(null, null, NOV_1, NOV_30), " CSV "));

        assertThat(file.fileName()).isEqualTo("conciliacion-20261201-0905.csv");
        assertThat(file.contentType()).isEqualTo("text/csv");
        assertThat(new String(file.content(), StandardCharsets.UTF_8)).isEqualTo("2 filas en America/Lima");

        assertThatThrownBy(() -> TenantContext.callAs(new TenantInfo(organization, ana, "ADMINISTRATOR"),
                () -> exports.export(new ReconciliationFilter(null, null, null, null), "xlsx")))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "UNSUPPORTED_FORMAT");
        assertThatThrownBy(() -> TenantContext.callAs(new TenantInfo(organization, ana, "ADMINISTRATOR"),
                () -> exports.export(new ReconciliationFilter(null, null, null, null), null)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void projector_tolerates_incomplete_events_and_out_of_order_arrival() {
        UUID d5 = UUID.randomUUID();
        UUID l5 = UUID.randomUUID();
        UUID ol5 = UUID.randomUUID();
        // La conformidad llega antes que la salida: crea la línea con lo despachado del evento.
        ObjectNode confirmed = dispatchPayload(d5, torreNorte, "MAN-5");
        confirmed.put("confirmedAt", "no-es-fecha");
        confirmed.putArray("lines")
                .add(confirmedLine(l5, ol5, cemento, null, null, null, "10", "10", "0", "2", true))
                .add(obj("dispatchLineId", UUID.randomUUID().toString(), "received", "1"))
                .add(obj("received", "x"))
                .add(obj("orderLineId", UUID.randomUUID().toString(), "received", "2"));
        record("buildshield.ordering", "ReceptionConfirmed", d5, at("2026-11-15T10:00:00Z"), confirmed);
        var early = readModel.findLine(organization, l5).orElseThrow();
        assertThat(early.status()).isEqualTo(LineStatus.RECEIVED);
        assertThat(early.departedAt()).isNull();
        assertThat(early.receivedAt()).isEqualTo(at("2026-11-15T10:00:00Z"));
        assertThat(early.periodDate()).isEqualTo(at("2026-11-15T10:00:00Z"));

        ObjectNode departure = departurePayload(d5, torreNorte, "MAN-5", "2026-11-14T10:00:00Z",
                line(l5, ol5, cemento, "12"));
        departure.withArray("lines").add(obj("dispatchLineId", UUID.randomUUID().toString()))
                .add(obj("materialId", cemento.toString(), "quantity", "1"));
        record("buildshield.ordering", "DispatchDeparted", d5, at("2026-11-14T10:00:30Z"), departure);
        var completed = readModel.findLine(organization, l5).orElseThrow();
        assertThat(completed.departedAt()).isEqualTo(at("2026-11-14T10:00:00Z"));
        assertThat(completed.dispatched()).isEqualByComparingTo("10");
        assertThat(completed.status()).isEqualTo(LineStatus.RECEIVED);

        // Una parcial que llega tarde no cambia una línea conforme; una de otra línea del pedido se ignora.
        ObjectNode late = dispatchPayload(d5, torreNorte, "MAN-5");
        late.putArray("lines").add(obj("orderLineId", ol5.toString(), "receivedQuantity", "3"))
                .add(obj("orderLineId", UUID.randomUUID().toString(), "receivedQuantity", "3"))
                .add(obj("receivedQuantity", "3"));
        record("buildshield.ordering", "ReceptionPartiallyReceived", d5, at("2026-11-15T11:00:00Z"), late);
        assertThat(readModel.findLine(organization, l5).orElseThrow().received()).isEqualByComparingTo("10");

        // Conformidad sin dispatchLineId: se ubica por la línea del pedido.
        UUID d6 = UUID.randomUUID();
        UUID ol6 = UUID.randomUUID();
        UUID l6 = UUID.randomUUID();
        departed(d6, torreNorte, "MAN-6", "2026-11-16T10:00:00Z", line(l6, ol6, fierro, "8"));
        ObjectNode byOrderLine = dispatchPayload(d6, torreNorte, "MAN-6");
        byOrderLine.putArray("lines").add(obj("orderLineId", ol6.toString(), "received", "8", "withinTolerance",
                "true"));
        record("buildshield.ordering", "ReceptionConfirmed", d6, at("2026-11-16T12:00:00Z"), byOrderLine);
        assertThat(readModel.findLine(organization, l6).orElseThrow().status()).isEqualTo(LineStatus.RECEIVED);

        // Eventos sin datos útiles: se registran y no tocan el read model.
        int lines = readModel.lines.size();
        int discrepancies = readModel.discrepancies.size();
        for (String type : List.of("DispatchDeparted", "DispatchCancelled", "ReceptionPartiallyReceived",
                "ReceptionConfirmed", "LocationRegistered", "MaterialUpdated", "DiscrepancyOpened")) {
            record("buildshield.ordering", type, null, at("2026-11-17T10:00:00Z"), obj("name", "Sin id"));
        }
        projector.apply("DispatchDeparted", organization, null, NOW, obj("dispatchId", "no-uuid"));
        projector.apply("DiscrepancyEscalated", organization, "no-uuid", NOW, obj());
        projector.apply("OrderRegistered", organization, null, NOW, obj());
        assertThat(readModel.lines).hasSize(lines);
        assertThat(readModel.discrepancies).hasSize(discrepancies);
        assertThat(ReconciliationProjector.EVENT_TYPES).doesNotContain("OrderRegistered");
    }

    @Test
    void discrepancy_without_payload_id_uses_the_aggregate_id_and_keeps_its_opening_date() {
        UUID id = UUID.randomUUID();
        projector.apply("DiscrepancyOpened", organization, id.toString(), at("2026-11-05T10:00:00Z"),
                obj("worksiteId", torreNorte.toString(), "quantity", "abc"));
        projector.apply("DiscrepancyEscalated", organization, id.toString(), at("2026-11-06T10:00:00Z"),
                obj("type", "DAMAGE"));

        var discrepancy = readModel.findDiscrepancy(organization, id).orElseThrow();
        assertThat(discrepancy.status()).isEqualTo("ESCALATED");
        assertThat(discrepancy.openedAt()).isEqualTo(at("2026-11-05T10:00:00Z"));
        assertThat(discrepancy.worksiteId()).isEqualTo(torreNorte);
        assertThat(discrepancy.typeLabel()).isEqualTo("Daño");
        assertThat(discrepancy.quantity()).isNull();
    }

    // ---------- Ayudas ----------

    private ReconciliationReport as(UUID user, String role, ReconciliationFilter filter) {
        return TenantContext.callAs(new TenantInfo(organization, user, role), () -> queries.report(filter));
    }

    private void record(String topic, String type, UUID aggregateId, Instant at, ObjectNode payload) {
        commands.record(new EventEnvelope(UUID.randomUUID(), type, at, organization, "X",
                aggregateId == null ? null : aggregateId.toString(), null, 1, payload));
        projector.apply(type, organization, aggregateId == null ? null : aggregateId.toString(), at, payload);
    }

    private void assign(UUID user, UUID location, String type) {
        UUID assignment = UUID.randomUUID();
        record("buildshield.organization", "StaffAssigned", assignment, at("2026-10-01T10:00:00Z"), obj(
                "assignmentId", assignment.toString(), "userId", user.toString(), "locationId", location.toString(),
                "locationType", type));
    }

    private void location(UUID id, String type, String name) {
        record("buildshield.organization", "LocationRegistered", id, at("2026-10-01T10:00:00Z"),
                obj("locationId", id.toString(), "type", type, "name", name));
    }

    private void material(UUID id, String sku, String name, String unit, String tolerance) {
        record("buildshield.organization", "MaterialRegistered", id, at("2026-10-01T10:00:00Z"),
                obj("materialId", id.toString(), "sku", sku, "name", name, "unit", unit, "tolerancePercent",
                        tolerance));
    }

    private void departed(UUID dispatch, UUID worksite, String manifest, String departedAt, ObjectNode... lines) {
        record("buildshield.ordering", "DispatchDeparted", dispatch, at(departedAt),
                departurePayload(dispatch, worksite, manifest, departedAt, lines));
    }

    private ObjectNode departurePayload(UUID dispatch, UUID worksite, String manifest, String departedAt,
            ObjectNode... lines) {
        ObjectNode payload = dispatchPayload(dispatch, worksite, manifest);
        payload.put("departedAt", departedAt);
        ArrayNode array = payload.putArray("lines");
        for (ObjectNode line : lines) {
            array.add(line);
        }
        return payload;
    }

    private ObjectNode dispatchPayload(UUID dispatch, UUID worksite, String manifest) {
        return obj("dispatchId", dispatch.toString(), "worksiteId", worksite.toString(), "warehouseId",
                almacen.toString(), "manifestCode", manifest);
    }

    private static ObjectNode line(UUID lineId, UUID orderLineId, UUID material, String quantity) {
        return obj("dispatchLineId", lineId.toString(), "orderLineId", orderLineId.toString(), "materialId",
                material.toString(), "quantity", quantity);
    }

    private static ObjectNode confirmedLine(UUID lineId, UUID orderLineId, UUID material, String sku, String name,
            String unit, String dispatched, String received, String shrinkage, String tolerance, boolean within) {
        ObjectNode line = obj("dispatchLineId", lineId.toString(), "orderLineId", orderLineId.toString(),
                "materialId", material.toString(), "sku", sku, "materialName", name, "unit", unit, "dispatched",
                dispatched, "received", received, "shrinkagePercent", shrinkage, "tolerancePercent", tolerance);
        line.put("withinTolerance", within);
        return line;
    }

    private void discrepancy(String type, UUID id, UUID dispatch, UUID worksite, UUID material, String status,
            String at) {
        ObjectNode payload = obj("discrepancyId", id.toString(), "dispatchId", dispatch.toString(), "manifestCode",
                dispatch.equals(d1) ? "MAN-1" : null, "worksiteId", worksite.toString(), "warehouseId",
                almacen.toString(), "materialId", material.toString(), "sku", "CEM-001", "materialName",
                "Cemento Portland", "type", "SHORTAGE", "quantity", "3", "status", status);
        record("buildshield.discrepancy", type, id, at(at), payload);
    }

    private static ObjectNode obj(String... pairs) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] == null) {
                node.putNull(pairs[i]);
            } else {
                node.put(pairs[i], pairs[i + 1]);
            }
        }
        return node;
    }

    private static Instant at(String value) {
        return Instant.parse(value);
    }

    /** Renderizador de prueba: deja ver qué reporte y qué zona recibió. */
    private static final class FakeRenderer implements ReconciliationRenderer {

        @Override
        public String format() {
            return "csv";
        }

        @Override
        public String contentType() {
            return "text/csv";
        }

        @Override
        public byte[] render(ReconciliationReport report, ZoneId zone) {
            return (report.rows().size() + " filas en " + zone.getId()).getBytes(StandardCharsets.UTF_8);
        }
    }
}
