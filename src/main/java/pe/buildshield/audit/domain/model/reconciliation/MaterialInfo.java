package pe.buildshield.audit.domain.model.reconciliation;

import java.math.BigDecimal;
import java.util.UUID;

/** Material del catálogo de organization, copiado de sus eventos. */
public record MaterialInfo(UUID materialId, String sku, String name, String unit, BigDecimal tolerancePercent) {
}
