package pe.buildshield.audit.domain.model;

/**
 * Resultado de recorrer la cadena de huellas de una organización.
 *
 * @param valid          todas las huellas coinciden y cada entrada sigue a la anterior
 * @param entriesChecked cuántas entradas se revisaron (hasta la primera alterada, si la hay)
 * @param brokenEntryId  id de la primera entrada alterada; nulo si la cadena es válida
 * @param reason         qué falló; nulo si la cadena es válida
 */
public record ChainVerification(boolean valid, long entriesChecked, Long brokenEntryId, String reason) {

    /** Revisa las entradas de una organización una por una, en el orden en que se anexaron. */
    public static final class Verifier {

        private String expectedPrevHash = AuditEntry.GENESIS_HASH;
        private long checked;
        private Long brokenEntryId;
        private String reason;

        /** Ignora lo que llegue después de la primera entrada alterada. */
        public void accept(AuditEntry entry) {
            if (brokenEntryId != null) {
                return;
            }
            checked++;
            if (!entry.prevHash().equals(expectedPrevHash)) {
                break_(entry, "no sigue a la entrada anterior (falta, sobra o cambió de lugar una entrada)");
            } else if (!entry.hashMatches()) {
                break_(entry, "su huella no corresponde a sus datos (la entrada fue alterada)");
            } else {
                expectedPrevHash = entry.hash();
            }
        }

        public ChainVerification result() {
            return new ChainVerification(brokenEntryId == null, checked, brokenEntryId, reason);
        }

        private void break_(AuditEntry entry, String why) {
            brokenEntryId = entry.id();
            reason = why;
        }
    }
}
