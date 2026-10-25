package pe.buildshield.audit.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.buildshield.audit.application.AuditQueryService;
import pe.buildshield.audit.application.AuditQueryService.AuditEventView;
import pe.buildshield.audit.domain.model.AuditEntry;
import pe.buildshield.audit.domain.model.AuditFilter;
import pe.buildshield.audit.domain.model.ChainVerification;
import pe.buildshield.audit.shared.error.ErrorResponse;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Auditoría", description = "QAS09: registro de solo anexado de todos los eventos de la organización")
@SecurityRequirement(name = "bearer")
class AuditController {

    private final AuditQueryService queries;
    private final ObjectMapper json;

    AuditController(AuditQueryService queries, ObjectMapper json) {
        this.queries = queries;
        this.json = json;
    }

    @GetMapping("/events")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Operation(summary = "Consultar el registro de auditoría",
            description = "Eventos de la organización del token, del más reciente al más antiguo, con filtros "
                    + "opcionales. Solo el administrador.")
    @ApiResponse(responseCode = "200", description = "Página de eventos",
            content = @Content(schema = @Schema(implementation = AuditEventsResource.class),
                    examples = @ExampleObject(value = AuditApiExamples.EVENTS)))
    @ApiResponse(responseCode = "403", description = "El usuario no es administrador",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    AuditEventsResource events(
            @Parameter(description = "Acción registrada (tipo de evento)", example = "DispatchDeparted")
            @RequestParam(required = false) String eventType,
            @Parameter(description = "Tipo de agregado", example = "DISPATCH")
            @RequestParam(required = false) String aggregateType,
            @Parameter(description = "Id del agregado", example = AuditApiExamples.DISPATCH_ID)
            @RequestParam(required = false) UUID aggregateId,
            @Parameter(description = "Usuario que provocó el evento", example = AuditApiExamples.USER_ID)
            @RequestParam(required = false) UUID actorId,
            @Parameter(description = "Desde (incluido), ISO-8601", example = "2026-11-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Hasta (excluido), ISO-8601", example = "2026-12-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "Página desde 0", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máximo 200)", example = "50")
            @RequestParam(defaultValue = "50") int size) {
        var view = queries.search(new AuditFilter(eventType, aggregateType, aggregateId, actorId, from, to, page, size));
        return new AuditEventsResource(view.items().stream().map(this::resource).toList(), view.page(), view.size(),
                view.total());
    }

    private AuditEventResource resource(AuditEventView view) {
        var entry = view.entry();
        UUID actor = AuditEntry.SYSTEM_USER_ID.equals(entry.actorUserId()) ? null : entry.actorUserId();
        return new AuditEventResource(entry.id(), entry.eventId(), entry.action(), entry.aggregateType(),
                entry.aggregateId(), actor, view.actorName(), entry.occurredAt(), read(entry.beforeValue()),
                read(entry.afterValue()), entry.prevHash(), entry.hash());
    }

    private JsonNode read(String value) {
        if (value == null) {
            return null;
        }
        try {
            return json.readTree(value);
        } catch (IOException ex) {
            return json.createObjectNode();
        }
    }

    @GetMapping("/verify")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Operation(summary = "Verificar la cadena de huellas",
            description = "Recorre la bitácora de la organización en el orden en que se anexó y recalcula la huella "
                    + "SHA-256 de cada entrada. Si todas coinciden y cada una sigue a la anterior, ninguna fue "
                    + "alterada. Solo el administrador.")
    @ApiResponse(responseCode = "200", description = "Resultado de la verificación",
            content = @Content(schema = @Schema(implementation = ChainVerificationResource.class),
                    examples = @ExampleObject(value = AuditApiExamples.VERIFICATION)))
    @ApiResponse(responseCode = "403", description = "El usuario no es administrador",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ChainVerificationResource verify() {
        ChainVerification result = queries.verify();
        return new ChainVerificationResource(result.valid(), result.entriesChecked(), result.brokenEntryId(),
                result.reason());
    }

    @Schema(description = "Entrada de la bitácora de auditoría")
    record AuditEventResource(@Schema(description = "Posición en la bitácora") long id, UUID eventId,
            @Schema(description = "Acción registrada: el tipo del evento", example = "DispatchDeparted") String eventType,
            String aggregateType, UUID aggregateId,
            @Schema(description = "Usuario que provocó la acción; nulo si la hizo el sistema") UUID actorId,
            String actorName, Instant occurredAt,
            @Schema(description = "Estado anterior; nulo si el evento no lo informa") JsonNode beforeValue,
            @Schema(description = "Estado posterior: el payload del evento tal como se publicó") JsonNode payload,
            @Schema(description = "Huella de la entrada anterior de la organización (64 ceros en la primera)")
            String prevHash,
            @Schema(description = "SHA-256 de esta entrada encadenada a la anterior") String hash) {
    }

    @Schema(description = "Resultado de recorrer la cadena de huellas")
    record ChainVerificationResource(@Schema(description = "true si ninguna entrada fue alterada") boolean valid,
            @Schema(description = "Entradas revisadas") long entriesChecked,
            @Schema(description = "Primera entrada alterada; nulo si la cadena es válida") Long brokenEntryId,
            @Schema(description = "Qué falló; nulo si la cadena es válida") String reason) {
    }

    @Schema(description = "Página del registro de auditoría")
    record AuditEventsResource(List<AuditEventResource> items, int page, int size, long total) {
    }
}
