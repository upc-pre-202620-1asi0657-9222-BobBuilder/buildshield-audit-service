package pe.buildshield.audit.domain.model.reconciliation;

/** Estado de una línea de despacho en la conciliación, según el último evento que la tocó. */
public enum LineStatus {
    IN_TRANSIT("En tránsito"),
    PARTIALLY_RECEIVED("Recepción parcial"),
    RECEIVED("Recibido"),
    CANCELLED("Anulado");

    private final String label;

    LineStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Todavía falta la conformidad de la obra (y el despacho no se anuló). */
    public boolean pendingReception() {
        return this == IN_TRANSIT || this == PARTIALLY_RECEIVED;
    }
}
