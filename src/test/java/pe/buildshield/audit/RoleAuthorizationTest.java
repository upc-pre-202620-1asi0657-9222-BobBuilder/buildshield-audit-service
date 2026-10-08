package pe.buildshield.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pe.buildshield.audit.application.AuditQueryService;
import pe.buildshield.audit.application.CustodyQueryService;
import pe.buildshield.audit.shared.security.JwtTokenIssuer;
import pe.buildshield.audit.shared.security.RevokedTokenStore;
import pe.buildshield.audit.shared.tenant.TenantInfo;
import pe.buildshield.audit.support.WebSliceTest;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Capa web con la seguridad real: el registro solo para el administrador; la custodia para los tres roles. */
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
