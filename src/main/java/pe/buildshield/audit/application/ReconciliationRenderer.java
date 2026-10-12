package pe.buildshield.audit.application;

import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport;

import java.time.ZoneId;

/**
 * Puerto de salida de la exportación del reporte de conciliación (US48). Cada formato (CSV, PDF) es un adaptador de
 * infraestructura.
 */
public interface ReconciliationRenderer {

    /** Formato que pide el cliente, en minúsculas: {@code csv} o {@code pdf}. */
    String format();

    String contentType();

    /**
     * @param zone zona horaria para mostrar la fecha de generación y las fechas de los casos pendientes
     */
    byte[] render(ReconciliationReport report, ZoneId zone);
}
