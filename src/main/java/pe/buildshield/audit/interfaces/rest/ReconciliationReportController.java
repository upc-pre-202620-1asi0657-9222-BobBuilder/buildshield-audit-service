package pe.buildshield.audit.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.buildshield.audit.application.ReconciliationExportService;
import pe.buildshield.audit.application.ReconciliationQueryService;
import pe.buildshield.audit.application.ReconciliationQueryService.ReconciliationFilter;
import pe.buildshield.audit.application.ReconciliationRebuildService;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport;
import pe.buildshield.audit.shared.error.ErrorResponse;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit/reports/reconciliation")
@Tag(name = "Conciliación", description = "US47 y US48: reporte de conciliación por obra, almacén y periodo, con "
        + "exportación a CSV y PDF")
@SecurityRequirement(name = "bearer")
class ReconciliationReportController {

    private final ReconciliationQueryService queries;
    private final ReconciliationExportService exports;
    private final ReconciliationRebuildService rebuild;

    ReconciliationReportController(ReconciliationQueryService queries, ReconciliationExportService exports,
            ReconciliationRebuildService rebuild) {
        this.queries = queries;
        this.exports = exports;
        this.rebuild = rebuild;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'WAREHOUSE_MANAGER', 'SITE_MANAGER')")
    @Operation(summary = "Consultar el reporte de conciliación",
            description = "Por obra, almacén y material: lo despachado, lo recibido, lo que falta recibir y la merma "
                    + "de lo ya conforme comparada con la tolerancia; totales por unidad y casos pendientes "
                    + "(despachos sin conformidad y discrepancias abiertas). El periodo se compara con la salida del "
                    + "almacén, en días de America/Lima con ambos extremos incluidos. El administrador ve toda la "
                    + "organización; el encargado de almacén, sus almacenes; el encargado de obra, sus obras.")
    @ApiResponse(responseCode = "200", description = "Reporte",
            content = @Content(schema = @Schema(implementation = ReconciliationReport.class),
                    examples = @ExampleObject(value = ReconciliationApiExamples.REPORT)))
    @ApiResponse(responseCode = "400", description = "Fecha desde posterior a la fecha hasta",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Obra o almacén fuera de lo asignado al encargado",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ReconciliationReport report(
            @Parameter(description = "Obra", example = ReconciliationApiExamples.WORKSITE_ID)
            @RequestParam(required = false) UUID worksiteId,
            @Parameter(description = "Almacén de origen", example = ReconciliationApiExamples.WAREHOUSE_ID)
            @RequestParam(required = false) UUID warehouseId,
            @Parameter(description = "Desde (incluido), AAAA-MM-DD", example = "2026-11-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Hasta (incluido), AAAA-MM-DD", example = "2026-11-30")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return queries.report(new ReconciliationFilter(worksiteId, warehouseId, from, to));
    }

    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'WAREHOUSE_MANAGER', 'SITE_MANAGER')")
    @Operation(summary = "Exportar el reporte de conciliación",
            description = "El mismo reporte filtrado, con los mismos permisos, como archivo descargable. CSV en "
                    + "UTF-8 con BOM, separador punto y coma y coma decimal (Excel en español); PDF A4 horizontal. "
                    + "Ambos llevan el encabezado BuildShield, los filtros aplicados y la fecha de generación.")
    @ApiResponse(responseCode = "200", description = "Archivo conciliacion-AAAAMMDD-HHMM.csv o .pdf",
            content = {@Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary"))})
    @ApiResponse(responseCode = "400", description = "Formato no soportado o periodo inválido",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Obra o almacén fuera de lo asignado al encargado",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ResponseEntity<byte[]> export(
            @Parameter(description = "Formato: csv o pdf", example = "pdf") @RequestParam String format,
            @Parameter(description = "Obra", example = ReconciliationApiExamples.WORKSITE_ID)
            @RequestParam(required = false) UUID worksiteId,
            @Parameter(description = "Almacén de origen", example = ReconciliationApiExamples.WAREHOUSE_ID)
            @RequestParam(required = false) UUID warehouseId,
            @Parameter(description = "Desde (incluido), AAAA-MM-DD", example = "2026-11-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Hasta (incluido), AAAA-MM-DD", example = "2026-11-30")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var file = exports.export(new ReconciliationFilter(worksiteId, warehouseId, from, to), format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName()).build().toString())
                .body(file.content());
    }

    @PostMapping("/rebuild")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Operation(summary = "Reconstruir el read model de conciliación",
            description = "Vacía el read model de la organización y vuelve a aplicar, en orden, los eventos del "
                    + "registro de solo anexado que lo alimentan (despachos, recepciones, discrepancias, obras, "
                    + "almacenes y materiales). Sirve para el historial anterior al reporte. Solo el administrador.")
    @ApiResponse(responseCode = "200", description = "Eventos aplicados",
            content = @Content(schema = @Schema(implementation = RebuildResource.class),
                    examples = @ExampleObject(value = ReconciliationApiExamples.REBUILD)))
    @ApiResponse(responseCode = "403", description = "El usuario no es administrador",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    RebuildResource rebuild() {
        return new RebuildResource(rebuild.rebuild());
    }

    @Schema(description = "Resultado de la reconstrucción")
    record RebuildResource(@Schema(description = "Eventos del registro que se volvieron a aplicar") int eventsReplayed) {
    }
}
