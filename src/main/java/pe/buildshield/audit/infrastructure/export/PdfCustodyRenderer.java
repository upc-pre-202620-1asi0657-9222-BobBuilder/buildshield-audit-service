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
import pe.buildshield.audit.application.CustodyQueryService.Custody;
import pe.buildshield.audit.application.CustodyQueryService.CustodyEvent;
import pe.buildshield.audit.application.CustodyRenderer;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * PDF de la custodia de un despacho (RNF03) con OpenPDF: A4 vertical, encabezado BuildShield, el despacho y su
 * manifiesto, la fecha de generación y una fila por paso (fecha, paso, responsable y detalle). Fuentes estándar
 * Helvetica y número de página al pie.
 */
@Component
class PdfCustodyRenderer implements CustodyRenderer {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private static final Color BRAND = new Color(0xC2, 0x41, 0x0C);
    private static final Color HEADER_BACKGROUND = new Color(0x1F, 0x29, 0x37);
    private static final Color MUTED = new Color(0x4B, 0x55, 0x63);

    private static final Font TITLE = new Font(Font.HELVETICA, 20, Font.BOLD, BRAND);
    private static final Font SUBTITLE = new Font(Font.HELVETICA, 13, Font.BOLD, HEADER_BACKGROUND);
    private static final Font META = new Font(Font.HELVETICA, 9, Font.NORMAL, MUTED);
    private static final Font HEAD = new Font(Font.HELVETICA, 8.5f, Font.BOLD, Color.WHITE);
    private static final Font CELL = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, Color.BLACK);
    private static final Font CELL_BOLD = new Font(Font.HELVETICA, 8.5f, Font.BOLD, Color.BLACK);

    @Override
    public String contentType() {
        return "application/pdf";
    }

    @Override
    public byte[] render(Custody custody, ZoneId zone, Instant generatedAt, String generatedBy) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 32, 32, 32, 40);
        PdfWriter.getInstance(document, out);
        document.addTitle(ReportTexts.TITLE + ": historial de custodia del despacho");
        document.addCreator(ReportTexts.TITLE);
        HeaderFooter footer = new HeaderFooter(new Phrase(ReportTexts.TITLE + ". Página ", META), true);
        footer.setAlignment(Element.ALIGN_RIGHT);
        footer.setBorder(Rectangle.NO_BORDER);
        document.setFooter(footer);
        document.open();

        document.add(new Paragraph(ReportTexts.TITLE, TITLE));
        document.add(new Paragraph("Historial de custodia del despacho", SUBTITLE));
        document.add(new Paragraph("Despacho: " + custody.dispatchId() + "    Manifiesto: "
                + (custody.manifestCode() == null ? "Sin manifiesto" : custody.manifestCode()), META));
        document.add(new Paragraph("Generado el " + DATE_TIME.format(generatedAt.atZone(zone)) + " (" + zone.getId()
                + ")" + (generatedBy == null ? "" : " por " + generatedBy) + ". " + custody.events().size()
                + (custody.events().size() == 1 ? " paso." : " pasos."), META));

        PdfPTable table = new PdfPTable(new float[]{17, 21, 20, 42});
        table.setWidthPercentage(100);
        table.setSpacingBefore(10);
        table.setHeaderRows(1);
        for (String title : new String[]{"Fecha y hora", "Paso", "Responsable", "Detalle"}) {
            PdfPCell cell = new PdfPCell(new Phrase(title, HEAD));
            cell.setBackgroundColor(HEADER_BACKGROUND);
            cell.setPadding(4);
            table.addCell(cell);
        }
        for (CustodyEvent event : custody.events()) {
            table.addCell(cell(DATE_TIME.format(event.at().atZone(zone)), CELL));
            table.addCell(cell(event.label(), CELL_BOLD));
            table.addCell(cell(event.actorName() == null ? (event.actorId() == null ? "Sistema" : "Usuario desconocido")
                    : event.actorName(), CELL));
            table.addCell(cell(event.detail() == null ? "" : event.detail(), CELL));
        }
        document.add(table);
        document.close();
        return out.toByteArray();
    }

    private static PdfPCell cell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setPadding(4);
        return cell;
    }
}
