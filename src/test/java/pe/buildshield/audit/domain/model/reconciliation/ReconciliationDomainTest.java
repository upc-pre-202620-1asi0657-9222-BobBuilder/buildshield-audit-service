package pe.buildshield.audit.domain.model.reconciliation;

import org.junit.jupiter.api.Test;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport.Filters;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport.ToleranceStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationDomainTest {

    private static final Instant T0 = Instant.parse("2026-11-03T13:00:00Z");
    private static final Filters NO_FILTERS = new Filters(null, null, null, null, null, null);

    private final UUID worksite = UUID.randomUUID();
    private final UUID warehouse = UUID.randomUUID();
    private final UUID material = UUID.randomUUID();

    @Test
    void line_quantities_follow_its_status() {
        ReconciliationLine inTransit = line(UUID.randomUUID(), "10");
        assertThat(inTransit.pendingQuantity()).isEqualByComparingTo("10");
        assertThat(inTransit.shrinkage()).isNull();

        ReconciliationLine partial = inTransit.partiallyReceived(new BigDecimal("12"));
        assertThat(partial.status()).isEqualTo(LineStatus.PARTIALLY_RECEIVED);
        assertThat(partial.pendingQuantity()).isEqualByComparingTo("0");

        ReconciliationLine received = partial.confirmed(null, new BigDecimal("9"), new BigDecimal("10"),
                new BigDecimal("2"), false, "CEM", "Cemento", "BAG", T0);
        assertThat(received.dispatched()).isEqualByComparingTo("10");
        assertThat(received.shrinkage()).isEqualByComparingTo("1");
        assertThat(received.pendingQuantity()).isEqualByComparingTo("0");
        assertThat(received.partiallyReceived(BigDecimal.ONE)).isSameAs(received);
        assertThat(received.cancelled().status()).isEqualTo(LineStatus.CANCELLED);
        assertThat(received.cancelled().partiallyReceived(BigDecimal.ONE).status()).isEqualTo(LineStatus.CANCELLED);
        assertThat(LineStatus.CANCELLED.label()).isEqualTo("Anulado");

        ReconciliationLine departedLater = inTransit.departed(UUID.randomUUID(), "MAN-9", null, null,
                new BigDecimal("11"), T0.plusSeconds(60));
        assertThat(departedLater.dispatched()).isEqualByComparingTo("11");
        assertThat(departedLater.manifestCode()).isEqualTo("MAN-1");
        assertThat(departedLater.departedAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void discrepancy_labels_and_changes() {
        DiscrepancyCase opened = new DiscrepancyCase(UUID.randomUUID(), null, null, worksite, null, material, null,
                null, "WRONG_MATERIAL", BigDecimal.ONE, "OPEN", T0);
        assertThat(opened.open()).isTrue();
        assertThat(opened.statusLabel()).isEqualTo("Abierta");
        assertThat(opened.typeLabel()).isEqualTo("Material equivocado");

        DiscrepancyCase accepted = opened.changed(new DiscrepancyCase(opened.discrepancyId(), UUID.randomUUID(),
                "MAN-1", null, warehouse, null, "CEM", "Cemento", null, null, "ACCEPTED", T0.plusSeconds(60)));
        assertThat(accepted.open()).isFalse();
        assertThat(accepted.openedAt()).isEqualTo(T0);
        assertThat(accepted.worksiteId()).isEqualTo(worksite);
        assertThat(accepted.warehouseId()).isEqualTo(warehouse);
        assertThat(accepted.type()).isEqualTo("WRONG_MATERIAL");
        assertThat(new DiscrepancyCase(UUID.randomUUID(), null, null, null, null, null, null, null, null, null,
                "NEW_STATE", T0).typeLabel()).isNull();
        assertThat(new DiscrepancyCase(UUID.randomUUID(), null, null, null, null, null, null, null, "OTHER", null,
                "RESOLVED", T0)).satisfies(d -> {
                    assertThat(d.open()).isFalse();
                    assertThat(d.typeLabel()).isEqualTo("OTHER");
                });
        assertThat(new DiscrepancyCase(UUID.randomUUID(), null, null, null, null, null, null, null, null, null,
                "NEW_STATE", T0).statusLabel()).isEqualTo("NEW_STATE");
    }

    @Test
    void criteria_filter_by_locations_and_half_open_period() {
        ReconciliationCriteria any = new ReconciliationCriteria(null, null, null, null);
        assertThat(any.matchesNothing()).isFalse();
        assertThat(any.inPeriod(null)).isTrue();

        ReconciliationCriteria november = new ReconciliationCriteria(Set.of(worksite), null, T0, T0.plusSeconds(60));
        assertThat(november.inPeriod(null)).isFalse();
        assertThat(november.inPeriod(T0)).isTrue();
        assertThat(november.inPeriod(T0.plusSeconds(60))).isFalse();
        assertThat(november.inPeriod(T0.minusSeconds(1))).isFalse();
        assertThat(november.includesLocations(null, warehouse)).isFalse();
        assertThat(november.includes(line(UUID.randomUUID(), "1"))).isTrue();
        assertThat(new ReconciliationCriteria(null, Set.of(), null, null).matchesNothing()).isTrue();
        assertThat(new ReconciliationCriteria(Set.of(), null, null, null).matchesNothing()).isTrue();
    }

    @Test
    void report_falls_back_to_the_catalog_and_to_short_ids_when_names_are_missing() {
        ReconciliationLine pending = line(UUID.randomUUID(), "5");
        ReconciliationLine noUnit = new ReconciliationLine(UUID.randomUUID(), UUID.randomUUID(), null, null, null,
                null, UUID.randomUUID(), null, null, null, new BigDecimal("2.500"), null, null, null, null,
                LineStatus.PARTIALLY_RECEIVED, null, T0);
        ReconciliationLine cancelled = line(UUID.randomUUID(), "7").cancelled();

        ReconciliationReport report = ReconciliationReport.build(NO_FILTERS, T0, null,
                List.of(pending, noUnit, cancelled), List.of(), Map.of(),
                Map.of(material, new MaterialInfo(material, "CEM-001", "Cemento", "BAG", null)));

        assertThat(report.rows()).hasSize(2);
        var cement = report.rows().stream().filter(row -> material.equals(row.materialId())).findFirst()
                .orElseThrow();
        assertThat(cement.sku()).isEqualTo("CEM-001");
        assertThat(cement.unit()).isEqualTo("BAG");
        assertThat(cement.worksiteName()).isEqualTo(worksite.toString().substring(0, 8));
        assertThat(cement.tolerancePercent()).isNull();
        assertThat(cement.toleranceStatus()).isEqualTo(ToleranceStatus.NOT_RECEIVED);
        var unknown = report.rows().stream().filter(row -> !material.equals(row.materialId())).findFirst()
                .orElseThrow();
        assertThat(unknown.worksiteName()).isNull();
        assertThat(unknown.dispatched()).isEqualByComparingTo("2.5");
        assertThat(report.totalsByUnit()).extracting(ReconciliationReport.UnitTotal::unit)
                .containsExactly("BAG", ReconciliationReport.NO_UNIT);
        assertThat(report.totalsByUnit().get(0).shrinkagePercent()).isNull();
        assertThat(report.pendingCases()).hasSize(2);
        assertThat(report.pendingCases()).extracting(ReconciliationReport.PendingCase::status)
                .containsExactlyInAnyOrder("En tránsito", "Recepción parcial");
        assertThat(report.pendingCases().get(1).since()).isNull();
        assertThat(report.summary().pendingReceptions()).isEqualTo(2);
    }

    @Test
    void shrinkage_is_compared_with_zero_when_the_tolerance_is_unknown_and_uses_the_latest_conformity() {
        ReconciliationLine first = line(UUID.randomUUID(), "10").confirmed(null, new BigDecimal("10"),
                BigDecimal.ZERO, new BigDecimal("5"), true, null, null, null, T0);
        ReconciliationLine second = line(UUID.randomUUID(), "10").confirmed(null, new BigDecimal("9"),
                BigDecimal.TEN, new BigDecimal("1"), false, null, null, null, T0.plusSeconds(60));
        ReconciliationLine noDate = line(UUID.randomUUID(), "10").confirmed(null, new BigDecimal("10"),
                BigDecimal.ZERO, new BigDecimal("9"), true, null, null, null, null);

        var row = ReconciliationReport.build(NO_FILTERS, T0, "Ana", List.of(first, second, noDate), List.of(),
                Map.of(), Map.of()).rows().get(0);
        assertThat(row.tolerancePercent()).isEqualByComparingTo("1");
        assertThat(row.shrinkagePercent()).isEqualByComparingTo("3.33");
        assertThat(row.toleranceStatus()).isEqualTo(ToleranceStatus.EXCEEDED);

        ReconciliationLine noTolerance = line(UUID.randomUUID(), "10").confirmed(null, new BigDecimal("10"),
                BigDecimal.ZERO, null, null, null, null, null, T0);
        var exact = ReconciliationReport.build(NO_FILTERS, T0, null, List.of(noTolerance), List.of(), Map.of(),
                Map.of()).rows().get(0);
        assertThat(exact.toleranceStatus()).isEqualTo(ToleranceStatus.WITHIN);
        assertThat(exact.tolerancePercent()).isNull();
        assertThat(ToleranceStatus.EXCEEDED.label()).isEqualTo("Supera la tolerancia");
        assertThat(ReconciliationReport.PendingCaseType.OPEN_DISCREPANCY.label()).isEqualTo("Discrepancia abierta");
    }

    @Test
    void open_discrepancies_without_type_or_sku_still_appear_as_pending_cases() {
        DiscrepancyCase bare = new DiscrepancyCase(UUID.randomUUID(), null, null, null, null, null, null, "Arena",
                null, null, "ESCALATED", T0);
        DiscrepancyCase noQuantity = new DiscrepancyCase(UUID.randomUUID(), null, null, null, null, null, "ARE-1",
                null, "EXCESS", null, "OPEN", T0.plusSeconds(1));

        var cases = ReconciliationReport.build(NO_FILTERS, T0, null, List.of(), List.of(bare, noQuantity), Map.of(),
                Map.of()).pendingCases();

        assertThat(cases.get(0).material()).isEqualTo("Arena");
        assertThat(cases.get(0).detail()).isNull();
        assertThat(cases.get(0).status()).isEqualTo("Escalada");
        assertThat(cases.get(1).material()).isEqualTo("ARE-1");
        assertThat(cases.get(1).detail()).isEqualTo("Exceso");
    }

    @Test
    void quantities_are_written_without_trailing_zeros_or_exponent() {
        assertThat(ReconciliationReport.quantity(new BigDecimal("100.000")).toPlainString()).isEqualTo("100");
        assertThat(ReconciliationReport.quantity(new BigDecimal("2.50")).toPlainString()).isEqualTo("2.5");
        assertThat(ReconciliationReport.quantity(new BigDecimal("1E+2")).scale()).isZero();
    }

    private ReconciliationLine line(UUID lineId, String quantity) {
        return ReconciliationLine.inTransit(lineId, UUID.randomUUID(), UUID.randomUUID(), "MAN-1", warehouse,
                worksite, material, new BigDecimal(quantity), T0);
    }
}
