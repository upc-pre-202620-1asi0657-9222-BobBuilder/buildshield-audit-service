package pe.buildshield.audit.application;

/**
 * Tipos de evento que audit usa además de registrarlos: copias locales de usuarios y asignaciones y read model de
 * conciliación (US47).
 */
public final class AuditEvents {

    public static final String USER_CREATED = "UserCreated";
    public static final String USER_UPDATED = "UserUpdated";
    public static final String STAFF_ASSIGNED = "StaffAssigned";
    public static final String STAFF_UNASSIGNED = "StaffUnassigned";
    public static final String LOCATION_REGISTERED = "LocationRegistered";
    public static final String LOCATION_UPDATED = "LocationUpdated";
    public static final String MATERIAL_REGISTERED = "MaterialRegistered";
    public static final String MATERIAL_UPDATED = "MaterialUpdated";
    public static final String DISPATCH_DEPARTED = "DispatchDeparted";
    public static final String DISPATCH_CANCELLED = "DispatchCancelled";
    public static final String RECEPTION_PARTIALLY_RECEIVED = "ReceptionPartiallyReceived";
    public static final String RECEPTION_CONFIRMED = "ReceptionConfirmed";
    public static final String DISCREPANCY_OPENED = "DiscrepancyOpened";
    public static final String DISCREPANCY_RESPONDED = "DiscrepancyResponded";
    public static final String DISCREPANCY_ESCALATED = "DiscrepancyEscalated";
    public static final String DISCREPANCY_ACCEPTED = "DiscrepancyAccepted";
    public static final String DISCREPANCY_RESOLVED = "DiscrepancyResolved";

    private AuditEvents() {
    }
}
