package pe.buildshield.audit.infrastructure.export;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;
import pe.buildshield.audit.domain.model.reconciliation.DiscrepancyCase;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationLine;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport.Filters;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationRenderersTest {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final Instant GENERATED = Instant.parse("2026-12-01T14:05:00Z");

    private final UUID worksite = UUID.randomUUID();
    private final UUID warehouse = UUID.randomUUID();
    private final UUID cement = UUID.randomUUID();

    @Test
    void csv_has_bom_header_filters_and_spanish_excel_separators() {
        CsvReconciliationRenderer csv = new CsvReconciliationRenderer(";", ",");
        assertThat(csv.format()).isEqualTo("csv");
        assertThat(csv.contentType()).isEqualTo("text/csv;charset=UTF-8");

        byte[] bytes = csv.render(report(), LIMA);

        assertThat(Arrays.copyOf(bytes, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        List<String> lines = text.lines().toList();
        assertThat(lines.get(0)).isEqualTo("BuildShield");
        assertThat(lines.get(1)).isEqualTo("Reporte de conciliación por obra y periodo");
        assertThat(lines).contains("Obra;Torre Norte", "Almacén;Todos", "Desde;01/11/2026", "Hasta;Sin límite",
                "Generado;01/12/2026 09:05 (America/Lima)", "Generado por;Ana Torres");
        assertThat(lines).contains("Torre Norte;Almacén Central;CEM-001;\"Cemento; tipo I\";BAG;1;50;47;0;3;6,00;2;"
                + "Supera la tolerancia;1;1");
        assertThat(lines).contains("BAG;50;47;0;3;6,00");
        assertThat(lines).anyMatch(line -> line.startsWith("Discrepancia abierta;MAN-1;Torre Norte;Almacén Central;"
                + "'=CMD;Abierta;03/11/2026 12:00;\"Faltante 3, dice \"\"urgente\"\"\""));
        assertThat(text).contains("\r\n");
    }

    @Test
    void csv_separators_are_configurable_for_point_decimal_locales() {
        String text = new String(new CsvReconciliationRenderer(",", ".").render(report(), LIMA),
                StandardCharsets.UTF_8);

        assertThat(text).contains("Torre Norte,Almacén Central,CEM-001,Cemento; tipo I,BAG,1,50,47,0,3,6.00,2,");
        assertThat(CsvReconciliationRenderer.text(null)).isEmpty();
        assertThat(CsvReconciliationRenderer.text("+51 999")).isEqualTo("'+51 999");
        assertThat(CsvReconciliationRenderer.text("-")).isEqualTo("'-");
        assertThat(CsvReconciliationRenderer.text("@x")).isEqualTo("'@x");
        assertThat(CsvReconciliationRenderer.text("")).isEmpty();
    }

    @Test
    void pdf_has_the_buildshield_header_filters_generation_date_and_tables() throws Exception {
        PdfReconciliationRenderer pdf = new PdfReconciliationRenderer();
        assertThat(pdf.format()).isEqualTo("pdf");
        assertThat(pdf.contentType()).isEqualTo("application/pdf");

        byte[] bytes = pdf.render(report(), LIMA);

        assertThat(new String(bytes, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        String text = new PdfTextExtractor(new PdfReader(bytes)).getTextFromPage(1);
        assertThat(text).contains("BuildShield", "Reporte de conciliación por obra y periodo", "Obra: Torre Norte",
                "Almacén: Todos", "Periodo: 01/11/2026 a Sin límite", "Generado el 01/12/2026 09:05",
                "por Ana Torres", "Cemento; tipo I", "Supera la", "Totales por unidad", "Casos pendientes",
                "Faltante 3", "1 despacho,", "1 material supera la tolerancia", "1 discrepancia abierta", "6,00");
    }

    @Test
    void pdf_of_an_empty_report_explains_there_is_nothing() throws Exception {
        ReconciliationReport empty = ReconciliationReport.build(new Filters(worksite, null, null, null, null, null),
                GENERATED, null, List.of(), List.of(), Map.of(), Map.of());

        byte[] bytes = new PdfReconciliationRenderer().render(empty, LIMA);

        String text = new PdfTextExtractor(new PdfReader(bytes)).getTextFromPage(1);
        assertThat(text).contains("Sin despachos para los filtros elegidos", "Sin totales",
                "No hay despachos sin conformidad ni discrepancias abiertas",
                "Obra: " + worksite.toString().substring(0, 8));
        assertThat(PdfReconciliationRenderer.number(null)).isEmpty();
        assertThat(PdfReconciliationRenderer.number(new BigDecimal("1234.5"))).isEqualTo("1.234,5");
        assertThat(PdfReconciliationRenderer.percent(null)).isEmpty();
        assertThat(PdfReconciliationRenderer.percent(new BigDecimal("6"))).isEqualTo("6,00");
        assertThat(text).contains("0 despachos", "0 discrepancias abiertas");
    }

    private ReconciliationReport report() {
        Instant departed = Instant.parse("2026-11-03T13:00:00Z");
        ReconciliationLine line = ReconciliationLine.inTransit(UUID.randomUUID(), UUID.randomUUID(), null, "MAN-1",
                warehouse, worksite, cement, new BigDecimal("50"), departed).confirmed(null, new BigDecimal("47"),
                new BigDecimal("6.00"), new BigDecimal("2"), false, "CEM-001", "Cemento; tipo I", "BAG",
                departed.plusSeconds(3600));
        DiscrepancyCase quoted = new DiscrepancyCase(UUID.randomUUID(), line.dispatchId(), "MAN-1",
                worksite, warehouse, cement, null, "=CMD", "SHORTAGE", new BigDecimal("3"), "OPEN",
                Instant.parse("2026-11-03T17:00:00Z"));
        ReconciliationReport base = ReconciliationReport.build(
                new Filters(worksite, "Torre Norte", null, null, LocalDate.parse("2026-11-01"), null), GENERATED,
                "Ana Torres", List.of(line), List.of(quoted), Map.of(worksite, "Torre Norte", warehouse,
                        "Almacén Central"), Map.of());
        // Detalle con comillas para probar el escape del CSV.
        var pending = base.pendingCases().get(0);
        var withQuotes = new ReconciliationReport.PendingCase(pending.type(), pending.dispatchId(),
                pending.manifestCode(), pending.discrepancyId(), pending.worksiteName(), pending.warehouseName(),
                pending.material(), pending.status(), pending.since(), "Faltante 3, dice \"urgente\"");
        return new ReconciliationReport(base.filters(), base.generatedAt(), base.generatedBy(), base.rows(),
                base.totalsByUnit(), base.summary(), List.of(withQuotes));
    }
}
