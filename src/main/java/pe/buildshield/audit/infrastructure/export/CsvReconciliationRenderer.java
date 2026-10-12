package pe.buildshield.audit.infrastructure.export;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pe.buildshield.audit.application.ReconciliationRenderer;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport.MaterialRow;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport.PendingCase;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport.UnitTotal;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CSV del reporte de conciliación (US48), pensado para abrirse con doble clic en Excel en español:
 *
 * <ul>
 *   <li>UTF-8 con BOM: sin él, Excel lo lee como ANSI y rompe las tildes y la ñ.</li>
 *   <li>Separador {@code ;} y coma decimal por defecto: es la configuración regional de Excel en español con coma
 *       decimal (España y buena parte de Latinoamérica), donde la coma ya es el separador decimal y la lista usa
 *       punto y coma. Si la empresa usa punto decimal (Windows en es-PE o es-MX), se cambia con
 *       {@code buildshield.reports.csv.separator=,} y {@code buildshield.reports.csv.decimal-separator=.}. No se usa
 *       la línea {@code sep=;} porque hace que Excel ignore el BOM.</li>
 *   <li>Encabezado con BuildShield, los filtros y la fecha de generación; luego la tabla por material, los totales
 *       por unidad y los casos pendientes, separados por una línea en blanco.</li>
 *   <li>Los textos que empiezan con {@code = + - @} se anteponen con {@code '} para que Excel no los ejecute como
 *       fórmula (inyección CSV).</li>
 * </ul>
 */
@Component
class CsvReconciliationRenderer implements ReconciliationRenderer {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String LINE_END = "\r\n";

    private final String separator;
    private final String decimalSeparator;

    CsvReconciliationRenderer(@Value("${buildshield.reports.csv.separator:;}") String separator,
            @Value("${buildshield.reports.csv.decimal-separator:,}") String decimalSeparator) {
        this.separator = separator;
        this.decimalSeparator = decimalSeparator;
    }

    @Override
    public String format() {
        return "csv";
    }

    @Override
    public String contentType() {
        return "text/csv;charset=UTF-8";
    }

    @Override
    public byte[] render(ReconciliationReport report, ZoneId zone) {
        StringBuilder csv = new StringBuilder();
        var filters = report.filters();
        line(csv, ReportTexts.TITLE);
        line(csv, ReportTexts.SUBTITLE);
        line(csv, "Obra", ReportTexts.orAll(filters.worksiteName(), filters.worksiteId(), "Todas"));
        line(csv, "Almacén", ReportTexts.orAll(filters.warehouseName(), filters.warehouseId(), "Todos"));
        line(csv, "Desde", ReportTexts.date(filters.from()));
        line(csv, "Hasta", ReportTexts.date(filters.to()));
        line(csv, "Generado", ReportTexts.dateTime(report.generatedAt(), zone) + " (" + zone.getId() + ")");
        line(csv, "Generado por", report.generatedBy() == null ? "" : report.generatedBy());
        csv.append(LINE_END);

        line(csv, "Obra", "Almacén", "SKU", "Material", "Unidad", "Despachos", "Despachado", "Recibido",
                "Por recibir", "Merma", "Merma %", "Tolerancia %", "Estado", "Líneas fuera de tolerancia",
                "Discrepancias abiertas");
        for (MaterialRow row : report.rows()) {
            line(csv, text(row.worksiteName()), text(row.warehouseName()), text(row.sku()), text(row.materialName()),
                    text(row.unit()), String.valueOf(row.dispatchCount()), number(row.dispatched()),
                    number(row.received()), number(row.pendingReception()), number(row.shrinkage()),
                    number(row.shrinkagePercent()), number(row.tolerancePercent()), row.toleranceStatus().label(),
                    String.valueOf(row.linesOutOfTolerance()), String.valueOf(row.openDiscrepancies()));
        }
        csv.append(LINE_END);

        line(csv, "Totales por unidad");
        line(csv, "Unidad", "Despachado", "Recibido", "Por recibir", "Merma", "Merma %");
        for (UnitTotal total : report.totalsByUnit()) {
            line(csv, text(total.unit()), number(total.dispatched()), number(total.received()),
                    number(total.pendingReception()), number(total.shrinkage()), number(total.shrinkagePercent()));
        }
        var summary = report.summary();
        line(csv, "Despachos", String.valueOf(summary.dispatchCount()));
        line(csv, "Despachos con recepción pendiente", String.valueOf(summary.pendingReceptions()));
        line(csv, "Materiales que superan la tolerancia", String.valueOf(summary.rowsExceedingTolerance()));
        line(csv, "Discrepancias abiertas", String.valueOf(summary.openDiscrepancies()));
        csv.append(LINE_END);

        line(csv, "Casos pendientes");
        line(csv, "Tipo", "Manifiesto", "Obra", "Almacén", "Material", "Estado", "Desde", "Detalle");
        for (PendingCase pending : report.pendingCases()) {
            line(csv, pending.type().label(), text(pending.manifestCode()), text(pending.worksiteName()),
                    text(pending.warehouseName()), text(pending.material()), text(pending.status()),
                    pending.since() == null ? "" : ReportTexts.dateTime(pending.since(), zone),
                    text(pending.detail()));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(BOM);
        out.writeBytes(csv.toString().getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private void line(StringBuilder csv, String... cells) {
        List<String> escaped = new ArrayList<>();
        Arrays.stream(cells).forEach(cell -> escaped.add(escape(cell)));
        csv.append(String.join(separator, escaped)).append(LINE_END);
    }

    private String escape(String cell) {
        if (cell == null) {
            return "";
        }
        if (cell.contains(separator) || cell.contains("\"") || cell.contains("\n") || cell.contains("\r")) {
            return "\"" + cell.replace("\"", "\"\"") + "\"";
        }
        return cell;
    }

    /** Texto libre: neutraliza fórmulas. Los números no pasan por aquí (una merma negativa empieza con -). */
    static String text(String value) {
        if (value == null) {
            return "";
        }
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    private String number(BigDecimal value) {
        return value == null ? "" : value.toPlainString().replace(".", decimalSeparator);
    }
}
