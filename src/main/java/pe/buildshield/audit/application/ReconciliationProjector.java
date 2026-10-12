package pe.buildshield.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import pe.buildshield.audit.domain.model.reconciliation.DiscrepancyCase;
import pe.buildshield.audit.domain.model.reconciliation.LineStatus;
import pe.buildshield.audit.domain.model.reconciliation.MaterialInfo;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationLine;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReadModel;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static pe.buildshield.audit.application.AuditCommandService.text;
import static pe.buildshield.audit.application.AuditCommandService.uuid;

/**
 * Proyecta los eventos de ordering, discrepancy y organization sobre el read model de conciliación (US47). Lo usan el
 * consumo en vivo (misma transacción que el registro del evento) y la reconstrucción desde {@code audit_log}, así que
 * aplicar los mismos eventos en el mismo orden deja siempre el mismo resultado. Tolera campos ausentes: lo que no puede
 * ubicar lo ignora.
 */
@Component
public class ReconciliationProjector {

    /** Tipos de evento que alimentan el reporte; la reconstrucción solo recorre estos. */
    public static final Set<String> EVENT_TYPES = Set.of(AuditEvents.LOCATION_REGISTERED,
            AuditEvents.LOCATION_UPDATED, AuditEvents.MATERIAL_REGISTERED, AuditEvents.MATERIAL_UPDATED,
            AuditEvents.DISPATCH_DEPARTED, AuditEvents.DISPATCH_CANCELLED, AuditEvents.RECEPTION_PARTIALLY_RECEIVED,
            AuditEvents.RECEPTION_CONFIRMED, AuditEvents.DISCREPANCY_OPENED, AuditEvents.DISCREPANCY_RESPONDED,
            AuditEvents.DISCREPANCY_ESCALATED, AuditEvents.DISCREPANCY_ACCEPTED, AuditEvents.DISCREPANCY_RESOLVED);

    private static final Map<String, String> STATUS_BY_EVENT = Map.of(
            AuditEvents.DISCREPANCY_OPENED, "OPEN",
            AuditEvents.DISCREPANCY_RESPONDED, "RESPONDED",
            AuditEvents.DISCREPANCY_ESCALATED, "ESCALATED",
            AuditEvents.DISCREPANCY_ACCEPTED, "ACCEPTED",
            AuditEvents.DISCREPANCY_RESOLVED, "RESOLVED");

    private final ReconciliationReadModel readModel;

    public ReconciliationProjector(ReconciliationReadModel readModel) {
        this.readModel = readModel;
    }

    public boolean handles(String eventType) {
        return EVENT_TYPES.contains(eventType);
    }

    public void apply(String eventType, UUID organizationId, String aggregateId, Instant occurredAt,
            JsonNode payload) {
        switch (eventType) {
            case AuditEvents.LOCATION_REGISTERED, AuditEvents.LOCATION_UPDATED -> location(organizationId, occurredAt,
                    payload);
            case AuditEvents.MATERIAL_REGISTERED, AuditEvents.MATERIAL_UPDATED -> material(organizationId, occurredAt,
                    payload);
            case AuditEvents.DISPATCH_DEPARTED -> departed(organizationId, occurredAt, payload);
            case AuditEvents.DISPATCH_CANCELLED -> cancelled(organizationId, occurredAt, payload);
            case AuditEvents.RECEPTION_PARTIALLY_RECEIVED -> partial(organizationId, occurredAt, payload);
            case AuditEvents.RECEPTION_CONFIRMED -> confirmed(organizationId, occurredAt, payload);
            case AuditEvents.DISCREPANCY_OPENED, AuditEvents.DISCREPANCY_RESPONDED, AuditEvents.DISCREPANCY_ESCALATED,
                 AuditEvents.DISCREPANCY_ACCEPTED, AuditEvents.DISCREPANCY_RESOLVED -> discrepancy(eventType,
                    organizationId, aggregateId, occurredAt, payload);
            default -> {
                // No alimenta el reporte.
            }
        }
    }

    private void location(UUID organizationId, Instant at, JsonNode payload) {
        UUID locationId = uuid(payload, "locationId");
        String name = text(payload, "name");
        if (locationId != null && name != null) {
            readModel.saveLocation(organizationId, locationId, payload.path("type").asText(""), name, at);
        }
    }

    private void material(UUID organizationId, Instant at, JsonNode payload) {
        UUID materialId = uuid(payload, "materialId");
        if (materialId != null) {
            readModel.saveMaterial(organizationId, new MaterialInfo(materialId, text(payload, "sku"),
                    text(payload, "name"), text(payload, "unit"), decimal(payload, "tolerancePercent")), at);
        }
    }

    private void departed(UUID organizationId, Instant at, JsonNode payload) {
        UUID dispatchId = uuid(payload, "dispatchId");
        if (dispatchId == null) {
            return;
        }
        Instant departedAt = instant(payload, "departedAt").orElse(at);
        for (JsonNode item : payload.path("lines")) {
            UUID lineId = uuid(item, "dispatchLineId");
            UUID materialId = uuid(item, "materialId");
            BigDecimal quantity = decimal(item, "quantity");
            if (lineId == null || materialId == null || quantity == null) {
                continue;
            }
            UUID orderLineId = uuid(item, "orderLineId");
            String manifest = text(payload, "manifestCode");
            UUID warehouseId = uuid(payload, "warehouseId");
            UUID worksiteId = uuid(payload, "worksiteId");
            ReconciliationLine line = readModel.findLine(organizationId, lineId)
                    .map(known -> known.departed(orderLineId, manifest, warehouseId, worksiteId, quantity, departedAt))
                    .orElseGet(() -> ReconciliationLine.inTransit(lineId, dispatchId, orderLineId, manifest,
                            warehouseId, worksiteId, materialId, quantity, departedAt));
            readModel.saveLine(organizationId, line, at);
        }
    }

    private void cancelled(UUID organizationId, Instant at, JsonNode payload) {
        UUID dispatchId = uuid(payload, "dispatchId");
        if (dispatchId == null) {
            return;
        }
        readModel.findLinesOfDispatch(organizationId, dispatchId)
                .forEach(line -> readModel.saveLine(organizationId, line.cancelled(), at));
    }

    private void partial(UUID organizationId, Instant at, JsonNode payload) {
        UUID dispatchId = uuid(payload, "dispatchId");
        if (dispatchId == null) {
            return;
        }
        var lines = readModel.findLinesOfDispatch(organizationId, dispatchId);
        for (JsonNode item : payload.path("lines")) {
            UUID orderLineId = uuid(item, "orderLineId");
            BigDecimal received = decimal(item, "receivedQuantity");
            if (orderLineId == null || received == null) {
                continue;
            }
            lines.stream().filter(line -> orderLineId.equals(line.orderLineId())).findFirst()
                    .ifPresent(line -> readModel.saveLine(organizationId, line.partiallyReceived(received), at));
        }
    }

    private void confirmed(UUID organizationId, Instant at, JsonNode payload) {
        UUID dispatchId = uuid(payload, "dispatchId");
        if (dispatchId == null) {
            return;
        }
        Instant receivedAt = instant(payload, "confirmedAt").orElse(at);
        var lines = readModel.findLinesOfDispatch(organizationId, dispatchId);
        for (JsonNode item : payload.path("lines")) {
            BigDecimal received = decimal(item, "received");
            if (received == null) {
                continue;
            }
            UUID lineId = uuid(item, "dispatchLineId");
            UUID orderLineId = uuid(item, "orderLineId");
            Optional<ReconciliationLine> known = lineId != null ? readModel.findLine(organizationId, lineId)
                    : lines.stream().filter(line -> orderLineId != null && orderLineId.equals(line.orderLineId()))
                    .findFirst();
            BigDecimal dispatched = decimal(item, "dispatched");
            UUID materialId = uuid(item, "materialId");
            if (known.isEmpty() && (lineId == null || dispatched == null || materialId == null)) {
                continue;
            }
            ReconciliationLine base = known.orElseGet(() -> new ReconciliationLine(lineId, dispatchId, orderLineId,
                    text(payload, "manifestCode"), uuid(payload, "warehouseId"), uuid(payload, "worksiteId"),
                    materialId, null, null, null, dispatched, null, null, null, null,
                    LineStatus.IN_TRANSIT, null, null));
            Boolean within = item.hasNonNull("withinTolerance") ? item.get("withinTolerance").asBoolean() : null;
            readModel.saveLine(organizationId, base.confirmed(dispatched, received,
                    decimal(item, "shrinkagePercent"), decimal(item, "tolerancePercent"), within, text(item, "sku"),
                    text(item, "materialName"), text(item, "unit"), receivedAt), at);
        }
    }

    private void discrepancy(String eventType, UUID organizationId, String aggregateId, Instant at,
            JsonNode payload) {
        UUID discrepancyId = uuid(payload, "discrepancyId");
        if (discrepancyId == null && aggregateId != null) {
            discrepancyId = parse(aggregateId);
        }
        if (discrepancyId == null) {
            return;
        }
        String status = text(payload, "status");
        DiscrepancyCase update = new DiscrepancyCase(discrepancyId, uuid(payload, "dispatchId"),
                text(payload, "manifestCode"), uuid(payload, "worksiteId"), uuid(payload, "warehouseId"),
                uuid(payload, "materialId"), text(payload, "sku"), text(payload, "materialName"),
                text(payload, "type"), decimal(payload, "quantity"),
                status != null ? status : STATUS_BY_EVENT.get(eventType), at);
        DiscrepancyCase merged = readModel.findDiscrepancy(organizationId, discrepancyId)
                .map(known -> known.changed(update)).orElse(update);
        readModel.saveDiscrepancy(organizationId, merged, at);
    }

    private static BigDecimal decimal(JsonNode payload, String field) {
        if (!payload.hasNonNull(field)) {
            return null;
        }
        try {
            return new BigDecimal(payload.get(field).asText());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Optional<Instant> instant(JsonNode payload, String field) {
        if (!payload.hasNonNull(field)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(payload.get(field).asText()));
        } catch (DateTimeParseException ex) {
            return Optional.empty();
        }
    }

    private static UUID parse(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
