package pe.buildshield.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pe.buildshield.audit.application.AuditQueryService;
import pe.buildshield.audit.application.CustodyExportService;
import pe.buildshield.audit.application.CustodyQueryService;
import pe.buildshield.audit.domain.model.ChainVerification;
import pe.buildshield.audit.application.ReconciliationExportService;
import pe.buildshield.audit.application.ReconciliationQueryService;
import pe.buildshield.audit.domain.model.reconciliation.ReconciliationReport;
import pe.buildshield.audit.shared.security.JwtTokenIssuer;
import pe.buildshield.audit.shared.security.RevokedTokenStore;
import pe.buildshield.audit.shared.tenant.TenantInfo;
import pe.buildshield.audit.support.WebSliceTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Capa web con la seguridad real: el registro y la reconstrucción solo para el administrador; la custodia y el
 * reporte de conciliación (con su exportación) para los tres roles.
 */
@WebMvcTest
@WebSliceTest
class RoleAuthorizationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtTokenIssuer issuer;

    @MockitoBean
    AuditQueryService audit;

    @MockitoBean
    CustodyQueryService custody;

    @MockitoBean
    RevokedTokenStore revokedTokens;

    @MockitoBean
    ReconciliationQueryService reconciliation;

    @MockitoBean
    ReconciliationExportService exports;

    @MockitoBean
    CustodyExportService custodyExports;

    @Test
    void every_role_reads_and_exports_the_reconciliation_report() throws Exception {
        when(reconciliation.report(any())).thenReturn(ReconciliationReport.build(
                new ReconciliationReport.Filters(null, null, null, null, null, null), Instant.EPOCH, null, List.of(),
                List.of(), Map.of(), Map.of()));
        when(exports.export(any(), eq("csv"))).thenReturn(new ReconciliationExportService.ExportedFile(
                "conciliacion-20261201-0905.csv", "text/csv;charset=UTF-8", "BuildShield".getBytes()));

        for (String role : List.of("ADMINISTRATOR", "WAREHOUSE_MANAGER", "SITE_MANAGER")) {
            mvc.perform(get("/api/v1/audit/reports/reconciliation").param("from", "2026-11-01")
                            .header("Authorization", bearer(role)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.summary.dispatchCount").value(0));
            mvc.perform(get("/api/v1/audit/reports/reconciliation/export").param("format", "csv")
                            .header("Authorization", bearer(role)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"conciliacion-20261201-0905.csv\""))
                    .andExpect(content().contentTypeCompatibleWith("text/csv"));
        }
        mvc.perform(get("/api/v1/audit/reports/reconciliation")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/audit/reports/reconciliation").param("from", "no-date")
                .header("Authorization", bearer("ADMINISTRATOR"))).andExpect(status().isBadRequest());
    }

    @Test
    void only_the_administrator_verifies_the_hash_chain() throws Exception {
        when(audit.verify()).thenReturn(new ChainVerification(true, 12, null, null));

        mvc.perform(get("/api/v1/audit/verify").header("Authorization", bearer("ADMINISTRATOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.entriesChecked").value(12));
        mvc.perform(get("/api/v1/audit/verify").header("Authorization", bearer("WAREHOUSE_MANAGER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit/verify").header("Authorization", bearer("SITE_MANAGER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit/verify")).andExpect(status().isUnauthorized());
    }

    @Test
    void the_rebuild_endpoint_no_longer_exists() throws Exception {
        mvc.perform(post("/api/v1/audit/reports/reconciliation/rebuild")
                        .header("Authorization", bearer("ADMINISTRATOR")))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void every_role_exports_the_custody_pdf_and_anonymous_users_do_not() throws Exception {
        UUID dispatch = UUID.randomUUID();
        when(custodyExports.export(dispatch)).thenReturn(new CustodyExportService.ExportedFile(
                "custodia-MAN-1-20261201-0905.pdf", "application/pdf", "%PDF-".getBytes()));

        for (String role : List.of("ADMINISTRATOR", "WAREHOUSE_MANAGER", "SITE_MANAGER")) {
            mvc.perform(get("/api/v1/dispatches/" + dispatch + "/custody").param("format", "pdf")
                            .header("Authorization", bearer(role)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/pdf"))
                    .andExpect(header().string("Content-Disposition", containsString("custodia-MAN-1-")));
        }
        mvc.perform(get("/api/v1/dispatches/" + dispatch + "/custody").param("format", "pdf"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void only_the_administrator_reads_the_audit_log() throws Exception {
        when(audit.search(any())).thenReturn(new AuditQueryService.AuditEventsView(List.of(), 0, 50, 0));

        mvc.perform(get("/api/v1/audit/events").header("Authorization", bearer("ADMINISTRATOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/audit/events").header("Authorization", bearer("WAREHOUSE_MANAGER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit/events").header("Authorization", bearer("SITE_MANAGER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit/events")).andExpect(status().isUnauthorized());
    }

    @Test
    void every_role_reads_the_custody_and_anonymous_users_do_not() throws Exception {
        UUID dispatch = UUID.randomUUID();
        when(custody.custody(dispatch)).thenReturn(new CustodyQueryService.Custody(dispatch, "MAN-1", List.of()));

        for (String role : List.of("ADMINISTRATOR", "WAREHOUSE_MANAGER", "SITE_MANAGER")) {
            mvc.perform(get("/api/v1/dispatches/" + dispatch + "/custody").header("Authorization", bearer(role)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.manifestCode").value("MAN-1"));
        }
        mvc.perform(get("/api/v1/dispatches/" + dispatch + "/custody")).andExpect(status().isUnauthorized());
    }

    private String bearer(String role) {
        return "Bearer " + issuer.issue(new TenantInfo(UUID.randomUUID(), UUID.randomUUID(), role)).value();
    }
}
