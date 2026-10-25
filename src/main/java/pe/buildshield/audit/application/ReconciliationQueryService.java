package pe.buildshield.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.audit.domain.model.AssignmentDirectory;
import pe.buildshield.audit.domain.model.AuditLogRepository;
import pe.buildshield.audit.domain.model.UserDirectory;
import pe.buildshield.audit.domain.model.reconciliation.DiscrepancyCase;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationCriteria;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationLine;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReadModel;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport;
import pe.buildshield.audit.shared.error.ErrorDetail;
import pe.buildshield.audit.shared.error.ForbiddenException;
import pe.buildshield.audit.shared.error.ValidationException;
import pe.buildshield.audit.shared.tenant.TenantContext;
import pe.buildshield.audit.shared.tenant.TenantInfo;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Reporte de conciliación por obra, almacén y periodo (US47), armado desde el read model que alimentan los eventos.
 *
 * <p>Quién lo ve:
 * <ul>
 *   <li>Administrador: toda la organización, con cualquier filtro.</li>
 *   <li>Encargado de almacén: solo los despachos que salieron de los almacenes que tiene asignados.</li>
 *   <li>Encargado de obra: solo los despachos a las obras que tiene asignadas.</li>
 * </ul>
 * Filtrar por una obra o un almacén fuera de su alcance responde 403. El periodo son días calendario en la zona de la
 * empresa ({@code buildshield.reports.zone}, por defecto America/Lima), con ambos extremos incluidos, y se compara con
 * la fecha de salida del despacho.
 */
@Service
public class ReconciliationQueryService {

    static final String ADMINISTRATOR = "ADMINISTRATOR";
    static final String WAREHOUSE_MANAGER = "WAREHOUSE_MANAGER";
    static final String SITE_MANAGER = "SITE_MANAGER";
    static final String WORKSITE = "WORKSITE";
    static final String WAREHOUSE = "WAREHOUSE";

    private final AuditLogRepository auditLog;
    private final ObjectMapper json;
    private final AssignmentDirectory assignments;
    private final UserDirectory users;
    private final Clock clock;
    private final ZoneId zone;

    public ReconciliationQueryService(AuditLogRepository auditLog, ObjectMapper json, AssignmentDirectory assignments,
            UserDirectory users, Clock clock, @Value("${buildshield.reports.zone:America/Lima}") String zone) {
        this.auditLog = auditLog;
        this.json = json;
        this.assignments = assignments;
        this.users = users;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    public ZoneId zone() {
        return zone;
    }

    @Transactional(readOnly = true)
    public ReconciliationReport report(ReconciliationFilter filter) {
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new ValidationException("INVALID_PERIOD", "La fecha desde no puede ser posterior a la fecha hasta",
                    List.of(new ErrorDetail("from", "Debe ser igual o anterior a " + filter.to())));
        }
        TenantInfo requester = TenantContext.require();
        UUID organizationId = requester.organizationId();
        ReconciliationCriteria criteria = criteria(requester, filter);
        ReconciliationReadModel readModel = criteria.matchesNothing() ? new InMemoryReconciliationReadModel()
                : replayed(organizationId);

        List<ReconciliationLine> lines = criteria.matchesNothing() ? List.of()
                : readModel.findLines(organizationId, criteria);
        List<DiscrepancyCase> discrepancies = criteria.matchesNothing() ? List.of()
                : readModel.findOpenDiscrepancies(organizationId, criteria);

        Set<UUID> locationIds = new HashSet<>();
        Stream.concat(Stream.of(filter.worksiteId(), filter.warehouseId()),
                Stream.concat(lines.stream().flatMap(line -> Stream.of(line.worksiteId(), line.warehouseId())),
                        discrepancies.stream().flatMap(d -> Stream.of(d.worksiteId(), d.warehouseId()))))
                .filter(Objects::nonNull).forEach(locationIds::add);
        Map<UUID, String> names = readModel.locationNames(organizationId, locationIds);
        var materials = readModel.materials(organizationId,
                lines.stream().map(ReconciliationLine::materialId).distinct().toList());
        String generatedBy = users.names(organizationId, List.of(requester.userId())).get(requester.userId());

        var filters = new ReconciliationReport.Filters(filter.worksiteId(),
                filter.worksiteId() == null ? null : names.get(filter.worksiteId()), filter.warehouseId(),
                filter.warehouseId() == null ? null : names.get(filter.warehouseId()), filter.from(), filter.to());
        return ReconciliationReport.build(filters, clock.instant(), generatedBy, lines, discrepancies, names,
                materials);
    }

    /**
     * Aplica sobre un read model en memoria, del más antiguo al más reciente, los eventos de {@code audit_log} de la
     * organización que alimentan el reporte (despachos, recepciones, discrepancias, obras, almacenes y materiales).
     */
    private ReconciliationReadModel replayed(UUID organizationId) {
        InMemoryReconciliationReadModel readModel = new InMemoryReconciliationReadModel();
        ReconciliationProjector projector = new ReconciliationProjector(readModel);
        auditLog.replay(organizationId, ReconciliationProjector.EVENT_TYPES, entry -> projector.apply(entry.action(),
                entry.organizationId(), entry.aggregateId().toString(), entry.occurredAt(), read(entry.afterValue())));
        return readModel;
    }

    private JsonNode read(String value) {
        try {
            return value == null ? json.createObjectNode() : json.readTree(value);
        } catch (IOException ex) {
            return json.createObjectNode();
        }
    }

    private ReconciliationCriteria criteria(TenantInfo requester, ReconciliationFilter filter) {
        Set<UUID> worksites = filter.worksiteId() == null ? null : Set.of(filter.worksiteId());
        Set<UUID> warehouses = filter.warehouseId() == null ? null : Set.of(filter.warehouseId());
        switch (requester.role()) {
            case ADMINISTRATOR -> {
                // Toda la organización.
            }
            case SITE_MANAGER -> worksites = restrict(worksites, assignments.locationsOf(
                    requester.organizationId(), requester.userId(), WORKSITE), "worksiteId");
            case WAREHOUSE_MANAGER -> warehouses = restrict(warehouses, assignments.locationsOf(
                    requester.organizationId(), requester.userId(), WAREHOUSE), "warehouseId");
            default -> throw new ForbiddenException("REPORT_FORBIDDEN", "Tu rol no puede consultar este reporte");
        }
        Instant from = filter.from() == null ? null : filter.from().atStartOfDay(zone).toInstant();
        Instant to = filter.to() == null ? null : filter.to().plusDays(1).atStartOfDay(zone).toInstant();
        return new ReconciliationCriteria(worksites, warehouses, from, to);
    }

    /** El filtro pedido debe estar entre lo asignado; sin filtro, todo lo asignado. */
    private static Set<UUID> restrict(Set<UUID> requested, Set<UUID> assigned, String field) {
        if (requested == null) {
            return assigned;
        }
        if (!assigned.containsAll(requested)) {
            throw new ForbiddenException("REPORT_SCOPE_FORBIDDEN",
                    "Solo puedes consultar la conciliación de lo que tienes asignado",
                    List.of(new ErrorDetail(field, "No está asignado a tu usuario")));
        }
        return requested;
    }

    /**
     * Filtros del reporte; los nulos no filtran.
     *
     * @param from desde (incluido)
     * @param to   hasta (incluido)
     */
    public record ReconciliationFilter(UUID worksiteId, UUID warehouseId, LocalDate from, LocalDate to) {
    }
}
