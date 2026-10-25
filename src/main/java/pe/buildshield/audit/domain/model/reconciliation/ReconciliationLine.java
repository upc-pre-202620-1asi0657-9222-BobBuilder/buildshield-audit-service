package pe.buildshield.audit.domain.model.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Una línea de despacho tal como la conoce el reporte de conciliación: lo despachado, lo recibido (acumulado en los
 * parciales o el definitivo de la conformidad) y, tras la conformidad, la merma y la tolerancia que calculó ordering.
 * Los datos del material ({@code sku}, {@code materialName}, {@code unit}) solo llegan con la conformidad; antes se
 * completan con el catálogo.
 *
 * @param withinTolerance {@code null} hasta la conformidad
 */
public record ReconciliationLine(UUID dispatchLineId, UUID dispatchId, UUID orderLineId, String manifestCode,
        UUID warehouseId, UUID worksiteId, UUID materialId, String sku, String materialName, String unit,
        BigDecimal dispatched, BigDecimal received, BigDecimal shrinkagePercent, BigDecimal tolerancePercent,
        Boolean withinTolerance, LineStatus status, Instant departedAt, Instant receivedAt) {

    public ReconciliationLine {
        Objects.requireNonNull(dispatchLineId, "dispatchLineId");
        Objects.requireNonNull(dispatchId, "dispatchId");
        Objects.requireNonNull(materialId, "materialId");
        Objects.requireNonNull(dispatched, "dispatched");
        Objects.requireNonNull(status, "status");
    }

    /** Línea nueva al salir del almacén, todavía en tránsito. */
    public static ReconciliationLine inTransit(UUID dispatchLineId, UUID dispatchId, UUID orderLineId,
            String manifestCode, UUID warehouseId, UUID worksiteId, UUID materialId, BigDecimal quantity,
            Instant departedAt) {
        return new ReconciliationLine(dispatchLineId, dispatchId, orderLineId, manifestCode, warehouseId, worksiteId,
                materialId, null, null, null, quantity, null, null, null, null, LineStatus.IN_TRANSIT, departedAt,
                null);
    }

    /** Fecha que ubica la línea en el periodo: la salida del almacén o, si no llegó, la conformidad. */
    public Instant periodDate() {
        return departedAt != null ? departedAt : receivedAt;
    }

    /** Lo que falta recibir: despachado menos lo recibido hasta ahora; cero si ya hay conformidad o se anuló. */
    public BigDecimal pendingQuantity() {
        if (!status.pendingReception()) {
            return BigDecimal.ZERO;
        }
        BigDecimal pending = dispatched.subtract(received == null ? BigDecimal.ZERO : received);
        return pending.signum() < 0 ? BigDecimal.ZERO : pending;
    }

    /** Merma de la línea en cantidad (despachado menos recibido); solo con la conformidad. */
    public BigDecimal shrinkage() {
        return status == LineStatus.RECEIVED && received != null ? dispatched.subtract(received) : null;
    }

    /** Salida del almacén sobre una línea que dejó una conformidad que llegó antes: completa lo que faltaba. */
    public ReconciliationLine departed(UUID orderLineId, String manifestCode, UUID warehouseId, UUID worksiteId,
            BigDecimal quantity, Instant departedAt) {
        return new ReconciliationLine(dispatchLineId, dispatchId, first(this.orderLineId, orderLineId),
                first(this.manifestCode, manifestCode), first(this.warehouseId, warehouseId),
                first(this.worksiteId, worksiteId), materialId, sku, materialName, unit,
                status == LineStatus.RECEIVED ? dispatched : quantity, received, shrinkagePercent, tolerancePercent,
                withinTolerance, status, departedAt, receivedAt);
    }

    /** Recepción parcial: lo recibido acumulado. No cambia una línea ya conforme ni anulada. */
    public ReconciliationLine partiallyReceived(BigDecimal receivedSoFar) {
        if (!status.pendingReception()) {
            return this;
        }
        return new ReconciliationLine(dispatchLineId, dispatchId, orderLineId, manifestCode, warehouseId, worksiteId,
                materialId, sku, materialName, unit, dispatched, receivedSoFar, shrinkagePercent, tolerancePercent,
                withinTolerance, LineStatus.PARTIALLY_RECEIVED, departedAt, receivedAt);
    }

    /**
     * Conformidad de la obra con la merma y la tolerancia que calculó ordering.
     *
     * @param dispatched lo despachado según la conformidad; si viene nulo se conserva el de la salida
     */
    public ReconciliationLine confirmed(BigDecimal dispatched, BigDecimal received, BigDecimal shrinkagePercent,
            BigDecimal tolerancePercent, Boolean withinTolerance, String sku, String materialName, String unit,
            Instant receivedAt) {
        return new ReconciliationLine(dispatchLineId, dispatchId, orderLineId, manifestCode, warehouseId, worksiteId,
                materialId, first(sku, this.sku), first(materialName, this.materialName), first(unit, this.unit),
                first(dispatched, this.dispatched), received, shrinkagePercent, tolerancePercent, withinTolerance,
                LineStatus.RECEIVED, departedAt, receivedAt);
    }

    /** Despacho anulado: la línea sale del reporte. */
    public ReconciliationLine cancelled() {
        return new ReconciliationLine(dispatchLineId, dispatchId, orderLineId, manifestCode, warehouseId, worksiteId,
                materialId, sku, materialName, unit, dispatched, received, shrinkagePercent, tolerancePercent,
                withinTolerance, LineStatus.CANCELLED, departedAt, receivedAt);
    }

    private static <T> T first(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }
}
