package pe.buildshield.audit.application;

import org.springframework.stereotype.Service;
import pe.buildshield.audit.application.ReconciliationQueryService.ReconciliationFilter;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport;
import pe.buildshield.audit.shared.error.ErrorDetail;
import pe.buildshield.audit.shared.error.ValidationException;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Exportación del reporte de conciliación filtrado (US48): el mismo reporte de la consulta, con los mismos permisos,
 * en el formato pedido. El nombre del archivo lleva la fecha y hora de generación en la zona de la empresa.
 */
@Service
public class ReconciliationExportService {

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final ReconciliationQueryService queries;
    private final Map<String, ReconciliationRenderer> renderers;

    public ReconciliationExportService(ReconciliationQueryService queries, List<ReconciliationRenderer> renderers) {
        this.queries = queries;
        this.renderers = renderers.stream()
                .collect(Collectors.toUnmodifiableMap(ReconciliationRenderer::format, Function.identity()));
    }

    public ExportedFile export(ReconciliationFilter filter, String format) {
        String key = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        ReconciliationRenderer renderer = renderers.get(key);
        if (renderer == null) {
            throw new ValidationException("UNSUPPORTED_FORMAT", "Formato de exportación no soportado",
                    List.of(new ErrorDetail("format", "Usa uno de " + renderers.keySet().stream().sorted().toList())));
        }
        ReconciliationReport report = queries.report(filter);
        String stamp = FILE_STAMP.format(report.generatedAt().atZone(queries.zone()));
        return new ExportedFile("conciliacion-" + stamp + "." + key, renderer.contentType(),
                renderer.render(report, queries.zone()));
    }

    public record ExportedFile(String fileName, String contentType, byte[] content) {
    }
}
