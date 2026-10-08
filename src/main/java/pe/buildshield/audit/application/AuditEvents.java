package pe.buildshield.audit.application;

/** Tipos de evento que audit usa además de registrarlos (copias locales de usuarios y asignaciones). */
public final class AuditEvents {

    public static final String USER_CREATED = "UserCreated";
    public static final String USER_UPDATED = "UserUpdated";
    public static final String STAFF_ASSIGNED = "StaffAssigned";
    public static final String STAFF_UNASSIGNED = "StaffUnassigned";

    private AuditEvents() {
    }
}
