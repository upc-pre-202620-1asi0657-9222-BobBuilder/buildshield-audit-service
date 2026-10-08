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
import pe.buildshield.audit.domain.model.AuditFilter;
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
            @Parameter(description = "Tipo de evento", example = "DispatchDeparted")
            @RequestParam(required = false) String eventType,
            @Parameter(description = "Tipo de agregado", example = "DISPATCH")
            @RequestParam(required = false) String aggregateType,
            @Parameter(description = "Id del agregado", example = AuditApiExamples.DISPATCH_ID)
            @RequestParam(required = false) String aggregateId,
            @Parameter(description = "Usuario que provocó el evento", example = AuditApiExamples.USER_ID)
            @RequestParam(required = false) UUID actorId,
            @Parameter(description = "Tópico de origen", example = "buildshield.ordering")
            @RequestParam(required = false) String topic,
            @Parameter(description = "Desde (incluido), ISO-8601", example = "2026-11-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Hasta (excluido), ISO-8601", example = "2026-12-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "Página desde 0", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máximo 200)", example = "50")
            @RequestParam(defaultValue = "50") int size) {
        var view = queries.search(new AuditFilter(eventType, aggregateType, aggregateId, actorId, topic, from, to, page,
                size));
        return new AuditEventsResource(view.items().stream().map(this::resource).toList(), view.page(), view.size(),
                view.total());
    }

    private AuditEventResource resource(AuditEventView view) {
        var entry = view.entry();
        JsonNode payload;
        try {
            payload = json.readTree(entry.payload());
        } catch (IOException ex) {
            payload = json.createObjectNode();
        }
        return new AuditEventResource(entry.id(), entry.eventId(), entry.topic(), entry.eventType(),
                entry.aggregateType(), entry.aggregateId(), entry.actorId(), view.actorName(), entry.occurredAt(),
                entry.recordedAt(), payload);
    }

    @Schema(description = "Evento registrado")
    record AuditEventResource(UUID id, UUID eventId, String topic, String eventType, String aggregateType,
            String aggregateId, UUID actorId, String actorName, Instant occurredAt, Instant recordedAt,
            @Schema(description = "Payload del evento tal como se publicó") JsonNode payload) {
    }

    @Schema(description = "Página del registro de auditoría")
    record AuditEventsResource(List<AuditEventResource> items, int page, int size, long total) {
    }
}
