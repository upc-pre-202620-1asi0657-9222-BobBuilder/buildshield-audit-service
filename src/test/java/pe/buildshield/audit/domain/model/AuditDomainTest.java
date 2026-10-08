package pe.buildshield.audit.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditDomainTest {

    @Test
    void filter_normalizes_page_size_and_blank_values() {
        AuditFilter filter = new AuditFilter(" ", "DISPATCH ", "", null, null, null, null, -3, 0);
        assertThat(filter.eventType()).isNull();
        assertThat(filter.aggregateType()).isEqualTo("DISPATCH");
        assertThat(filter.aggregateId()).isNull();
        assertThat(filter.page()).isZero();
        assertThat(filter.size()).isEqualTo(50);
        assertThat(new AuditFilter(null, null, null, null, null, null, null, 2, 500).size())
                .isEqualTo(AuditFilter.MAX_SIZE);
        assertThat(new AuditFilter(null, null, null, null, "buildshield.iam", null, null, 3, 20).offset())
                .isEqualTo(60);
    }

    @Test
    void custody_types_map_every_dispatch_reception_and_discrepancy_event() {
        assertThat(CustodyEventType.fromEventType("DispatchPrepared")).contains(CustodyEventType.PREPARED);
        assertThat(CustodyEventType.fromEventType("ReceptionConfirmed")).contains(CustodyEventType.RECEIVED);
        assertThat(CustodyEventType.fromEventType("TransitIncidentReported")).contains(CustodyEventType.INCIDENT);
        assertThat(CustodyEventType.fromEventType("DiscrepancyAccepted"))
                .contains(CustodyEventType.DISCREPANCY_ACCEPTED);
        assertThat(CustodyEventType.fromEventType("StockDeducted")).isEmpty();
        assertThat(CustodyEventType.values()).hasSize(15).allSatisfy(type -> {
            assertThat(type.label()).isNotBlank();
            assertThat(CustodyEventType.fromEventType(type.eventType())).contains(type);
        });
    }

    @Test
    void an_entry_needs_its_identity_type_organization_time_and_payload() {
        UUID organization = UUID.randomUUID();
        Instant now = Instant.now();
        AuditEntry entry = new AuditEntry(UUID.randomUUID(), UUID.randomUUID(), "buildshield.iam", "UserCreated",
                "USER", "x", organization, null, now, now, null, "{}");
        assertThat(entry.organizationId()).isEqualTo(organization);
        assertThatThrownBy(() -> new AuditEntry(UUID.randomUUID(), null, "t", "e", null, null, organization, null, now,
                now, null, "{}")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuditEntry(UUID.randomUUID(), UUID.randomUUID(), "t", "e", null, null,
                organization, null, now, now, null, null)).isInstanceOf(NullPointerException.class);
    }
}
