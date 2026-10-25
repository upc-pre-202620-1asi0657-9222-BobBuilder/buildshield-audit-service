package pe.buildshield.audit.domain.model.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Estado vigente de una discrepancia según los eventos de discrepancy. Está abierta mientras no se acepte ni se
 * resuelva ({@code OPEN}, {@code RESPONDED} o {@code ESCALATED}).
 */
public record DiscrepancyCase(UUID discrepancyId, UUID dispatchId, String manifestCode, UUID worksiteId,
        UUID warehouseId, UUID materialId, String sku, String materialName, String type, BigDecimal quantity,
        String status, Instant openedAt) {

    private static final Map<String, String> STATUS_LABELS = Map.of(
            "OPEN", "Abierta",
            "RESPONDED", "Respondida",
            "ESCALATED", "Escalada",
            "ACCEPTED", "Aceptada",
            "RESOLVED", "Resuelta");

    private static final Map<String, String> TYPE_LABELS = Map.of(
            "SHORTAGE", "Faltante",
            "DAMAGE", "Daño",
            "EXCESS", "Exceso",
            "WRONG_MATERIAL", "Material equivocado");

    public DiscrepancyCase {
        Objects.requireNonNull(discrepancyId, "discrepancyId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(openedAt, "openedAt");
    }

    public boolean open() {
        return !("ACCEPTED".equals(status) || "RESOLVED".equals(status));
    }

    public String statusLabel() {
        return STATUS_LABELS.getOrDefault(status, status);
    }

    /** Tipo en español, o el código si es uno nuevo; {@code null} si el evento no lo trajo. */
    public String typeLabel() {
        return type == null ? null : TYPE_LABELS.getOrDefault(type, type);
    }

    /** Aplica un cambio de estado sin perder lo que el evento nuevo no trae; la apertura no cambia. */
    public DiscrepancyCase changed(DiscrepancyCase update) {
        return new DiscrepancyCase(discrepancyId, first(update.dispatchId, dispatchId),
                first(update.manifestCode, manifestCode), first(update.worksiteId, worksiteId),
                first(update.warehouseId, warehouseId), first(update.materialId, materialId), first(update.sku, sku),
                first(update.materialName, materialName), first(update.type, type), first(update.quantity, quantity),
                update.status, openedAt);
    }

    private static <T> T first(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }
}
