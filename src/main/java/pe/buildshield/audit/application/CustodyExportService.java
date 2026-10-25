package pe.buildshield.audit.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import pe.buildshield.audit.application.CustodyQueryService.Custody;
import pe.buildshield.audit.domain.model.UserDirectory;
import pe.buildshield.audit.shared.tenant.TenantContext;
import pe.buildshield.audit.shared.tenant.TenantInfo;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Exportación a PDF de la custodia de un despacho (RNF03: en menos de 10 segundos). Es la misma línea de tiempo de
 * {@link CustodyQueryService}, con los mismos permisos: el encargado de obra solo exporta los despachos a sus obras.
 * El nombre del archivo lleva el manifiesto y la fecha y hora de generación en la zona de la empresa.
 */
@Service
public class CustodyExportService {

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final CustodyQueryService custody;
    private final CustodyRenderer renderer;
    private final UserDirectory users;
    private final Clock clock;
    private final ZoneId zone;

    public CustodyExportService(CustodyQueryService custody, CustodyRenderer renderer, UserDirectory users,
            Clock clock, @Value("${buildshield.reports.zone:America/Lima}") String zone) {
        this.custody = custody;
        this.renderer = renderer;
        this.users = users;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    public ExportedFile export(UUID dispatchId) {
        TenantInfo requester = TenantContext.require();
        Custody timeline = custody.custody(dispatchId);
        var generatedAt = clock.instant();
        String generatedBy = users.names(requester.organizationId(), List.of(requester.userId()))
                .get(requester.userId());
        String name = timeline.manifestCode() != null ? timeline.manifestCode() : dispatchId.toString();
        return new ExportedFile("custodia-" + name + "-" + FILE_STAMP.format(generatedAt.atZone(zone)) + ".pdf",
                renderer.contentType(), renderer.render(timeline, zone, generatedAt, generatedBy));
    }

    public record ExportedFile(String fileName, String contentType, byte[] content) {
    }
}
