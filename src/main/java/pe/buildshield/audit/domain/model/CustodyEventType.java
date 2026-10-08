package pe.buildshield.audit.domain.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Tipos de la línea de custodia de un despacho (US39) y el evento de integración del que salen. Los eventos que
 * no están aquí (por ejemplo, los de inventario) no forman parte de la custodia.
 */
public enum CustodyEventType {
    PREPARED("DispatchPrepared", "Despacho preparado"),
    CARRIER_ASSIGNED("DispatchCarrierAssigned", "Transportista asignado"),
    DEPARTURE_WEIGHED("DispatchDepartureWeighed", "Pesaje de salida"),
    DEPARTED("DispatchDeparted", "Salida a la obra"),
    INCIDENT("TransitIncidentReported", "Incidencia en ruta"),
    CANCELLED("DispatchCancelled", "Despacho anulado"),
    RECEPTION_OPENED("ReceptionOpened", "Recepción abierta"),
    ARRIVAL_WEIGHED("ArrivalWeighed", "Pesaje de llegada"),
    PARTIALLY_RECEIVED("ReceptionPartiallyReceived", "Recepción parcial"),
    RECEIVED("ReceptionConfirmed", "Recepción confirmada"),
    DISCREPANCY_OPENED("DiscrepancyOpened", "Discrepancia abierta"),
    DISCREPANCY_RESPONDED("DiscrepancyResponded", "Discrepancia respondida"),
    DISCREPANCY_ESCALATED("DiscrepancyEscalated", "Discrepancia escalada"),
    DISCREPANCY_RESOLVED("DiscrepancyResolved", "Discrepancia resuelta"),
    DISCREPANCY_ACCEPTED("DiscrepancyAccepted", "Discrepancia aceptada");

    private final String eventType;
    private final String label;

    CustodyEventType(String eventType, String label) {
        this.eventType = eventType;
        this.label = label;
    }

    public String eventType() {
        return eventType;
    }

    public String label() {
        return label;
    }

    public static Optional<CustodyEventType> fromEventType(String eventType) {
        return Arrays.stream(values()).filter(type -> type.eventType.equals(eventType)).findFirst();
    }
}
