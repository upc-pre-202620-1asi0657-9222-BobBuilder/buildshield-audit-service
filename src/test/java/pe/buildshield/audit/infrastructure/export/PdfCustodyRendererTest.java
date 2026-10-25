package pe.buildshield.audit.infrastructure.export;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;
import pe.buildshield.audit.application.CustodyQueryService.Custody;
import pe.buildshield.audit.application.CustodyQueryService.CustodyEvent;
import pe.buildshield.audit.domain.model.CustodyEventType;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PdfCustodyRendererTest {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private final PdfCustodyRenderer pdf = new PdfCustodyRenderer();

    @Test
    void the_pdf_shows_the_dispatch_the_generation_date_and_a_row_per_step() throws Exception {
        UUID dispatch = UUID.randomUUID();
        Custody custody = new Custody(dispatch, "MAN-0001", List.of(
                new CustodyEvent(Instant.parse("2026-11-03T17:00:00Z"), CustodyEventType.PREPARED,
                        "Despacho preparado", UUID.randomUUID(), "Rosa Quispe", "Manifiesto MAN-0001, 2 líneas"),
                new CustodyEvent(Instant.parse("2026-11-03T18:30:00Z"), CustodyEventType.DEPARTED,
                        "Salida a la obra", null, null, null)));

        byte[] bytes = pdf.render(custody, LIMA, Instant.parse("2026-12-01T14:05:00Z"), "Ana Torres");

        assertThat(pdf.contentType()).isEqualTo("application/pdf");
        PdfReader reader = new PdfReader(bytes);
        String text = new PdfTextExtractor(reader).getTextFromPage(1);
        assertThat(text).contains("BuildShield", "Historial de custodia del despacho", dispatch.toString(),
                "MAN-0001", "01/12/2026 09:05:00", "America/Lima", "Ana Torres", "2 pasos.", "03/11/2026 12:00:00",
                "Despacho preparado", "Rosa Quispe", "Manifiesto MAN-0001, 2 líneas", "Salida a la obra", "Sistema");
    }

    @Test
    void a_dispatch_without_manifest_or_generator_still_renders() throws Exception {
        Custody custody = new Custody(UUID.randomUUID(), null, List.of(
                new CustodyEvent(Instant.parse("2026-11-03T17:00:00Z"), CustodyEventType.PREPARED, "Despacho preparado",
                        UUID.randomUUID(), null, "")));

        byte[] bytes = pdf.render(custody, LIMA, Instant.parse("2026-12-01T14:05:00Z"), null);

        String text = new PdfTextExtractor(new PdfReader(bytes)).getTextFromPage(1);
        assertThat(text).contains("Sin manifiesto", "1 paso.", "Usuario desconocido");
    }
}
