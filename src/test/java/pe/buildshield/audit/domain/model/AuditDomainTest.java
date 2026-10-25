package pe.buildshield.audit.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditDomainTest {

    @Test
    void filter_normalizes_page_size_and_blank_values() {
        AuditFilter filter = new AuditFilter(" ", "DISPATCH ", null, null, null, null, -3, 0);
        assertThat(filter.action()).isNull();
        assertThat(filter.aggregateType()).isEqualTo("DISPATCH");
        assertThat(filter.aggregateId()).isNull();
        assertThat(filter.page()).isZero();
        assertThat(filter.size()).isEqualTo(50);
        assertThat(new AuditFilter(null, null, null, null, null, null, 2, 500).size())
                .isEqualTo(AuditFilter.MAX_SIZE);
        assertThat(new AuditFilter(null, null, null, null, null, null, 3, 20).offset())
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
    void an_entry_is_chained_with_the_sha256_of_its_fields() {
        UUID organization = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID event = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
        UUID aggregate = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID actor = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        Instant at = Instant.parse("2026-11-03T13:30:00.123456Z");

        AuditEntry entry = AuditEntry.chain(new NewAuditEntry(event, organization, "Dispatch", aggregate,
                "DispatchDeparted", actor, at, null, "{\"a\": 1}"), AuditEntry.GENESIS_HASH);

        String expected = sha256Hex("0".repeat(64) + event + organization + "Dispatch" + aggregate
                + "DispatchDeparted" + actor + "2026-11-03T13:30:00.123456Z" + "{\"a\": 1}");
        assertThat(entry.hash()).isEqualTo(expected).hasSize(64);
        assertThat(entry.prevHash()).isEqualTo(AuditEntry.GENESIS_HASH);
        assertThat(entry.id()).isNull();
        assertThat(entry.hashMatches()).isTrue();
    }

    @Test
    void any_changed_field_breaks_the_hash() {
        UUID organization = UUID.randomUUID();
        AuditEntry entry = AuditEntry.chain(new NewAuditEntry(UUID.randomUUID(), organization, "Order",
                UUID.randomUUID(), "OrderApproved", UUID.randomUUID(), Instant.now(), null, "{\"total\": 10}"),
                AuditEntry.GENESIS_HASH);

        assertThat(withAfterValue(entry, "{\"total\": 11}").hashMatches()).isFalse();
        assertThat(new AuditEntry(1L, entry.eventId(), entry.organizationId(), entry.aggregateType(),
                entry.aggregateId(), "OrderRejected", entry.actorUserId(), entry.occurredAt(), null,
                entry.afterValue(), entry.prevHash(), entry.hash()).hashMatches()).isFalse();
        assertThat(new AuditEntry(1L, entry.eventId(), entry.organizationId(), entry.aggregateType(),
                entry.aggregateId(), entry.action(), entry.actorUserId(), entry.occurredAt(), null,
                entry.afterValue(), "f".repeat(64), entry.hash()).hashMatches()).isFalse();
    }

    @Test
    void a_new_entry_is_normalized_to_what_the_database_stores() {
        UUID id = UUID.randomUUID();
        NewAuditEntry draft = new NewAuditEntry(id, id, "A".repeat(30), id, "B".repeat(60), null,
                Instant.parse("2026-11-03T13:30:00.123456789Z"), null, "{}");

        assertThat(draft.aggregateType()).hasSize(NewAuditEntry.MAX_AGGREGATE_TYPE);
        assertThat(draft.action()).hasSize(NewAuditEntry.MAX_ACTION);
        assertThat(draft.actorUserId()).isEqualTo(AuditEntry.SYSTEM_USER_ID);
        assertThat(draft.occurredAt()).isEqualTo(Instant.parse("2026-11-03T13:30:00.123456Z"));
        assertThatThrownBy(() -> new NewAuditEntry(null, id, "A", id, "B", null, Instant.now(), null, "{}"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void the_verifier_accepts_an_intact_chain_and_an_empty_one() {
        UUID organization = UUID.randomUUID();
        ChainVerification.Verifier verifier = new ChainVerification.Verifier();
        assertThat(verifier.result().valid()).isTrue();
        assertThat(verifier.result().entriesChecked()).isZero();

        String prev = AuditEntry.GENESIS_HASH;
        for (long i = 1; i <= 3; i++) {
            AuditEntry chained = AuditEntry.chain(new NewAuditEntry(UUID.randomUUID(), organization, "Order",
                    UUID.randomUUID(), "OrderRegistered", null, Instant.now(), null, "{}"), prev);
            verifier.accept(new AuditEntry(i, chained.eventId(), organization, chained.aggregateType(),
                    chained.aggregateId(), chained.action(), chained.actorUserId(), chained.occurredAt(), null,
                    chained.afterValue(), chained.prevHash(), chained.hash()));
            prev = chained.hash();
        }

        assertThat(verifier.result()).isEqualTo(new ChainVerification(true, 3, null, null));
    }

    private static AuditEntry withAfterValue(AuditEntry entry, String afterValue) {
        return new AuditEntry(entry.id(), entry.eventId(), entry.organizationId(), entry.aggregateType(),
                entry.aggregateId(), entry.action(), entry.actorUserId(), entry.occurredAt(), entry.beforeValue(),
                afterValue, entry.prevHash(), entry.hash());
    }

    private static String sha256Hex(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
