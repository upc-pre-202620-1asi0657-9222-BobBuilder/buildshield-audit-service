-- Alineación con el ER v4 (pág. 19, audit_db y Anexo A.5).
-- audit_log con las columnas del ER y la cadena de huellas SHA-256 por organización; processed_events con llave
-- (event_id, consumer); el reporte de conciliación se arma desde audit_log, así que sus read models se eliminan.
--
-- hash = SHA-256(prev_hash || event_id || organization_id || aggregate_type || aggregate_id || action ||
--                actor_user_id || occurred_at || after_value)
-- Cada valor va como texto: uuid en minúsculas con guiones, occurred_at en UTC con 6 decimales
-- (yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z') y after_value como jsonb::text (vacío si es nulo). prev_hash de la primera
-- entrada de cada organización son 64 ceros. AuditEntry.computeHash calcula lo mismo en Java.

ALTER TABLE audit_log RENAME TO audit_log_old;

CREATE TABLE audit_log (
    id              BIGSERIAL,
    event_id        UUID        NOT NULL,
    organization_id UUID        NOT NULL,
    aggregate_type  VARCHAR(20) NOT NULL,
    aggregate_id    UUID        NOT NULL,
    action          VARCHAR(40) NOT NULL,
    actor_user_id   UUID        NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    before_value    JSONB,
    after_value     JSONB,
    prev_hash       CHAR(64)    NOT NULL,
    hash            CHAR(64)    NOT NULL,
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    -- El mismo evento no entra dos veces.
    CONSTRAINT uk_audit_log_event_id UNIQUE (event_id),
    -- Una cadena por organización, sin bifurcaciones: dos entradas no pueden seguir a la misma.
    CONSTRAINT uk_audit_log_chain UNIQUE (organization_id, prev_hash)
);

-- actor_user_id: las acciones del sistema (sin usuario) llevan el usuario cero.
DO $$
DECLARE
    r        RECORD;
    org      UUID;
    prev     TEXT;
    agg      UUID;
    actor    UUID;
    act      TEXT;
    typ      TEXT;
    h        TEXT;
BEGIN
    FOR r IN SELECT * FROM audit_log_old ORDER BY organization_id, occurred_at, recorded_at, id LOOP
        IF org IS DISTINCT FROM r.organization_id THEN
            org := r.organization_id;
            prev := repeat('0', 64);
        END IF;
        agg := CASE WHEN r.aggregate_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                    THEN r.aggregate_id::uuid ELSE md5(COALESCE(r.aggregate_id, ''))::uuid END;
        actor := COALESCE(r.actor_id, '00000000-0000-0000-0000-000000000000'::uuid);
        act := left(r.event_type, 40);
        typ := left(COALESCE(r.aggregate_type, 'UNKNOWN'), 20);
        h := encode(sha256(convert_to(prev || r.event_id::text || r.organization_id::text || typ || agg::text || act
                || actor::text || to_char(r.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
                || COALESCE(r.payload::text, ''), 'UTF8')), 'hex');
        INSERT INTO audit_log (event_id, organization_id, aggregate_type, aggregate_id, action, actor_user_id,
                               occurred_at, after_value, prev_hash, hash)
        VALUES (r.event_id, r.organization_id, typ, agg, act, actor, r.occurred_at, r.payload, prev, h);
        prev := h;
    END LOOP;
END $$;

DROP TABLE audit_log_old;

CREATE INDEX idx_audit_log_org_time ON audit_log (organization_id, occurred_at DESC, id DESC);
CREATE INDEX idx_audit_log_org_aggregate ON audit_log (organization_id, aggregate_type, aggregate_id, occurred_at);
-- Línea de custodia: todos los eventos de un despacho (los de recepción y discrepancias llevan dispatchId).
CREATE INDEX idx_audit_log_org_dispatch ON audit_log (organization_id, (after_value ->> 'dispatchId'), occurred_at);

-- Solo anexado: un trigger rechaza UPDATE, DELETE y TRUNCATE (reject_history_mutation viene de V1) y el rol del
-- servicio solo tiene INSERT y SELECT.
CREATE TRIGGER audit_log_immutable BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION reject_history_mutation();
REVOKE ALL ON audit_log FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'audit_service') THEN
        REVOKE ALL ON audit_log FROM audit_service;
        GRANT SELECT, INSERT ON audit_log TO audit_service;
        GRANT USAGE ON SEQUENCE audit_log_id_seq TO audit_service;
    END IF;
END $$;

-- Los read models del reporte de conciliación (V2) se derivaban de audit_log: ahora el reporte se arma consultándolo.
DROP TABLE reconciliation_discrepancy;
DROP TABLE reconciliation_line;
DROP TABLE material_projection;
DROP TABLE location_projection;

-- processed_events (A.5): llave (event_id, consumer). Lo procesado hasta ahora lo hicieron los consumidores por tópico,
-- que ahora son AuditEventConsumer.
ALTER TABLE processed_events ADD COLUMN consumer VARCHAR(60) NOT NULL DEFAULT 'AuditEventConsumer';
ALTER TABLE processed_events ALTER COLUMN consumer DROP DEFAULT;
ALTER TABLE processed_events DROP CONSTRAINT processed_events_pkey;
ALTER TABLE processed_events ADD PRIMARY KEY (event_id, consumer);
