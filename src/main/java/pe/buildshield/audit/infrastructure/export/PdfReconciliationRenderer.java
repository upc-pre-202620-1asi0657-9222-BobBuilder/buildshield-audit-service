package pe.buildshield.audit.infrastructure.export;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.HeaderFooter;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;
import pe.buildshield.audit.application.ReconciliationRenderer;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport.MaterialRow;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport.PendingCase;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport.ToleranceStatus;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport.UnitTotal;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.util.Locale;

/**
 * PDF del reporte de conciliación (US48) con OpenPDF: A4 horizontal, encabezado BuildShield, filtros aplicados, fecha
 * de generación, la tabla por material (las filas que superan la tolerancia van resaltadas), los totales por unidad y
 * los casos pendientes. Fuentes estándar Helvetica (WinAnsi: tildes y ñ sin incrustar fuentes) y número de página al
 * pie.
 */
@Component
class PdfReconciliationRenderer implements ReconciliationRenderer {

    private static final Color BRAND = new Color(0xC2, 0x41, 0x0C);
    private static final Color HEADER_BACKGROUND = new Color(0x1F, 0x29, 0x37);
    private static final Color EXCEEDED_BACKGROUND = new Color(0xFE, 0xE2, 0xE2);
    private static final Color MUTED = new Color(0x4B, 0x55, 0x63);

    private static final Font TITLE = new Font(Font.HELVETICA, 20, Font.BOLD, BRAND);
    private static final Font SUBTITLE = new Font(Font.HELVETICA, 13, Font.BOLD, HEADER_BACKGROUND);
    private static final Font SECTION = new Font(Font.HELVETICA, 11, Font.BOLD, HEADER_BACKGROUND);
    private static final Font META = new Font(Font.HELVETICA, 9, Font.NORMAL, MUTED);
    private static final Font HEAD = new Font(Font.HELVETICA, 7.5f, Font.BOLD, Color.WHITE);
    private static final Font CELL = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, Color.BLACK);
    private static final Font CELL_BOLD = new Font(Font.HELVETICA, 7.5f, Font.BOLD, Color.BLACK);

    @Override
    public String format() {
        return "pdf";
    }

    @Override
    public String contentType() {
        return "application/pdf";
    }

    @Override
    public byte[] render(ReconciliationReport report, ZoneId zone) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4.rotate(), 28, 28, 28, 36);
        PdfWriter.getInstance(document, out);
        document.addTitle(ReportTexts.TITLE + ": " + ReportTexts.SUBTITLE);
        document.addCreator(ReportTexts.TITLE);
        HeaderFooter footer = new HeaderFooter(new Phrase(ReportTexts.TITLE + ". Página ", META), true);
        footer.setAlignment(Element.ALIGN_RIGHT);
        footer.setBorder(Rectangle.NO_BORDER);
        document.setFooter(footer);
        document.open();

        document.add(new Paragraph(ReportTexts.TITLE, TITLE));
        document.add(new Paragraph(ReportTexts.SUBTITLE, SUBTITLE));
        var filters = report.filters();
        document.add(new Paragraph("Obra: " + ReportTexts.orAll(filters.worksiteName(), filters.worksiteId(), "Todas")
                + "    Almacén: " + ReportTexts.orAll(filters.warehouseName(), filters.warehouseId(), "Todos")
                + "    Periodo: " + ReportTexts.date(filters.from()) + " a " + ReportTexts.date(filters.to()), META));
        document.add(new Paragraph("Generado el " + ReportTexts.dateTime(report.generatedAt(), zone) + " ("
                + zone.getId() + ")" + (report.generatedBy() == null ? "" : " por " + report.generatedBy()), META));
        var summary = report.summary();
        Paragraph totals = new Paragraph(count(summary.dispatchCount(), "despacho", "despachos") + ", "
                + summary.pendingReceptions() + " con recepción pendiente, "
                + count(summary.rowsExceedingTolerance(), "material supera", "materiales superan") + " la tolerancia, "
                + count(summary.openDiscrepancies(), "discrepancia abierta", "discrepancias abiertas") + ".",
                CELL_BOLD);
        totals.setSpacingBefore(6);
        totals.setSpacingAfter(8);
        document.add(totals);

        document.add(materials(report));
        section(document, "Totales por unidad");
        document.add(units(report));
        section(document, "Casos pendientes");
        if (report.pendingCases().isEmpty()) {
            document.add(new Paragraph("No hay despachos sin conformidad ni discrepancias abiertas.", META));
        } else {
            document.add(pending(report, zone));
        }
        document.close();
        return out.toByteArray();
    }

    private static PdfPTable materials(ReconciliationReport report) {
        PdfPTable table = table(new float[]{10, 10, 6.5f, 13, 5, 5, 8, 7, 7, 6, 6, 6, 9, 5.5f, 6.5f}, "Obra", "Almacén",
                "SKU", "Material", "Unidad", "Desp.", "Despachado", "Recibido", "Por recibir", "Merma", "Merma %",
                "Toler. %", "Estado", "Fuera de toler.", "Discrep. abiertas");
        if (report.rows().isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase("Sin despachos para los filtros elegidos.", CELL));
            empty.setColspan(15);
            empty.setPadding(4);
            table.addCell(empty);
            return table;
        }
        for (MaterialRow row : report.rows()) {
            Color background = row.toleranceStatus() == ToleranceStatus.EXCEEDED ? EXCEEDED_BACKGROUND : null;
            cell(table, row.worksiteName(), background, false);
            cell(table, row.warehouseName(), background, false);
            cell(table, row.sku(), background, false);
            cell(table, row.materialName(), background, false);
            cell(table, row.unit(), background, false);
            cell(table, String.valueOf(row.dispatchCount()), background, true);
            cell(table, number(row.dispatched()), background, true);
            cell(table, number(row.received()), background, true);
            cell(table, number(row.pendingReception()), background, true);
            cell(table, number(row.shrinkage()), background, true);
            cell(table, percent(row.shrinkagePercent()), background, true);
            cell(table, number(row.tolerancePercent()), background, true);
            cell(table, row.toleranceStatus().label(), background, false);
            cell(table, String.valueOf(row.linesOutOfTolerance()), background, true);
            cell(table, String.valueOf(row.openDiscrepancies()), background, true);
        }
        return table;
    }

    private static PdfPTable units(ReconciliationReport report) {
        PdfPTable table = table(new float[]{14, 12, 12, 12, 12, 10}, "Unidad", "Despachado", "Recibido",
                "Por recibir", "Merma", "Merma %");
        table.setWidthPercentage(60);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);
        for (UnitTotal total : report.totalsByUnit()) {
            cell(table, total.unit(), null, false);
            cell(table, number(total.dispatched()), null, true);
            cell(table, number(total.received()), null, true);
            cell(table, number(total.pendingReception()), null, true);
            cell(table, number(total.shrinkage()), null, true);
            cell(table, percent(total.shrinkagePercent()), null, true);
        }
        if (report.totalsByUnit().isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase("Sin totales.", CELL));
            empty.setColspan(6);
            table.addCell(empty);
        }
        return table;
    }

    private static PdfPTable pending(ReconciliationReport report, ZoneId zone) {
        PdfPTable table = table(new float[]{11, 12, 12, 12, 16, 9, 10, 18}, "Tipo", "Manifiesto", "Obra", "Almacén",
                "Material", "Estado", "Desde", "Detalle");
        for (PendingCase pending : report.pendingCases()) {
            cell(table, pending.type().label(), null, false);
            cell(table, pending.manifestCode(), null, false);
            cell(table, pending.worksiteName(), null, false);
            cell(table, pending.warehouseName(), null, false);
            cell(table, pending.material(), null, false);
            cell(table, pending.status(), null, false);
            cell(table, pending.since() == null ? null : ReportTexts.dateTime(pending.since(), zone), null, false);
            cell(table, pending.detail(), null, false);
        }
        return table;
    }

    private static void section(Document document, String title) {
        Paragraph paragraph = new Paragraph(title, SECTION);
        paragraph.setSpacingBefore(12);
        paragraph.setSpacingAfter(4);
        document.add(paragraph);
    }

    private static PdfPTable table(float[] widths, String... headers) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String header : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(header, HEAD));
            cell.setBackgroundColor(HEADER_BACKGROUND);
            cell.setPadding(4);
            table.addCell(cell);
        }
        return table;
    }

    private static void cell(PdfPTable table, String text, Color background, boolean numeric) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, CELL));
        cell.setPadding(3);
        if (numeric) {
            cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        }
        if (background != null) {
            cell.setBackgroundColor(background);
        }
        table.addCell(cell);
    }

    /** Porcentaje con 2 decimales (6,00), como lo calcula ordering. */
    static String percent(BigDecimal value) {
        if (value == null) {
            return "";
        }
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.of("es", "ES")));
        return format.format(value);
    }

    private static String count(int value, String singular, String plural) {
        return value + " " + (value == 1 ? singular : plural);
    }

    /** Coma decimal y punto de miles, como se lee en español. */
    static String number(BigDecimal value) {
        if (value == null) {
            return "";
        }
        DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.of("es", "ES")));
        format.setGroupingUsed(true);
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(Math.max(2, value.scale()));
        return format.format(value);
    }
}
