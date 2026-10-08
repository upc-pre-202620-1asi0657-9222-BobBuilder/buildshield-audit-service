package pe.buildshield.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import pe.buildshield.audit.support.AuditIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La especificación OpenAPI es pública y documenta los endpoints de audit con su esquema de seguridad. */
@AuditIntegrationTest
@AutoConfigureMockMvc
class OpenApiDocumentationIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void api_docs_are_public_and_describe_the_audit_endpoints() throws Exception {
        JsonNode spec = json.readTree(mvc.perform(get("/api/v1/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(spec.path("paths").fieldNames()).toIterable()
                .contains("/api/v1/audit/events", "/api/v1/dispatches/{id}/custody");
        assertThat(spec.at("/components/securitySchemes/bearer/scheme").asText()).isEqualTo("bearer");
    }
}
