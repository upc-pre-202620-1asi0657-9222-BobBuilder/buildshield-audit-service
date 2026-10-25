package pe.buildshield.audit.application;

import pe.buildshield.audit.application.CustodyQueryService.Custody;

import java.time.Instant;
import java.time.ZoneId;

/** Puerto de salida de la exportación de la custodia de un despacho (RNF03). El PDF es un adaptador de infraestructura. */
public interface CustodyRenderer {

    String contentType();

    /**
     * @param zone        zona horaria para mostrar las fechas
     * @param generatedAt cuándo se genera el documento
     * @param generatedBy nombre de quien lo genera; nulo si no se conoce
     */
    byte[] render(Custody custody, ZoneId zone, Instant generatedAt, String generatedBy);
}
