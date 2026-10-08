package pe.buildshield.audit.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.buildshield.audit.application.CustodyQueryService;
import pe.buildshield.audit.domain.model.CustodyEventType;
import pe.buildshield.audit.shared.error.ErrorResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dispatches")
@Tag(name = "Custodia", description = "US39: historial de custodia de un despacho, desde el registro de solo anexado")
@SecurityRequirement(name = "bearer")
class CustodyController {

    private final CustodyQueryService custody;

    CustodyController(CustodyQueryService custody) {
        this.custody = custody;
    }

    @GetMapping("/{id}/custody")
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'WAREHOUSE_MANAGER', 'SITE_MANAGER')")
    @Operation(summary = "Consultar el historial de custodia de un despacho",
            description = "Preparación, transportista, pesajes, salida, incidencias, anulación, recepción y "
                    + "discrepancias ordenados por fecha, con el responsable de cada paso. El encargado de obra solo "
                    + "consulta los despachos a sus obras.")
    @ApiResponse(responseCode = "200", description = "Línea de tiempo del despacho",
            content = @Content(schema = @Schema(implementation = CustodyResource.class),
                    examples = @ExampleObject(value = AuditApiExamples.CUSTODY)))
    @ApiResponse(responseCode = "404", description = "Sin eventos, de otra organización o de una obra no asignada",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    CustodyResource custody(@Parameter(description = "Id del despacho", example = AuditApiExamples.DISPATCH_ID)
            @PathVariable UUID id) {
        var view = custody.custody(id);
        return new CustodyResource(view.dispatchId(), view.manifestCode(), view.events().stream()
                .map(event -> new CustodyEventResource(event.at(), event.type(), event.label(), event.actorId(),
                        event.actorName(), event.detail()))
                .toList());
    }

    @Schema(description = "Paso de la custodia")
    record CustodyEventResource(Instant at, CustodyEventType type, String label, UUID actorId, String actorName,
            String detail) {
    }

    @Schema(description = "Historial de custodia")
    record CustodyResource(UUID dispatchId, String manifestCode, List<CustodyEventResource> events) {
    }
}
