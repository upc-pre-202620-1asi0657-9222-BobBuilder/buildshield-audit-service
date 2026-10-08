-- Trazabilidad y Auditoría (audit_db). Registro de solo anexado de todos los eventos de integración de los seis
-- tópicos (QAS05 integridad de la custodia, QAS09 auditabilidad), portado de V14 del Core: un trigger impide
-- UPDATE, DELETE y TRUNCATE. Los servicios no escriben auditoría local: audit la arma consumiendo sus eventos.
CREATE TABLE audit_log (
    id              UUID PRIMARY KEY,
    event_id        UUID         NOT NULL,
    topic           VARCHAR(60)  NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    aggregate_type  VARCHAR(50),
    aggregate_id    VARCHAR(100),
    organization_id UUID         NOT NULL,
    actor_id        UUID,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    recorded_at     TIMESTAMPTZ  NOT NULL,
    correlation_id  VARCHAR(64),
    payload         JSONB        NOT NULL,
    CONSTRAINT uk_audit_log_event UNIQUE (event_id)
);

CREATE INDEX idx_audit_log_organization ON audit_log (organization_id, occurred_at DESC, id DESC);
CREATE INDEX idx_audit_log_aggregate ON audit_log (organization_id, aggregate_type, aggregate_id, occurred_at);
-- Línea de custodia: todos los eventos de un despacho (los de recepción y discrepancias llevan dispatchId).
CREATE INDEX idx_audit_log_dispatch ON audit_log (organization_id, (payload ->> 'dispatchId'), occurred_at);

CREATE FUNCTION reject_history_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'El historial solo admite anexar registros' USING ERRCODE = '42501';
END;
$$;

CREATE TRIGGER audit_log_immutable BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION reject_history_mutation();
REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM PUBLIC;

-- Copia de los usuarios de iam (UserCreated, UserUpdated): nombre del responsable en la custodia y bloqueo de
-- tokens de usuarios desactivados o con otro rol.
CREATE TABLE user_projection (
    user_id         UUID PRIMARY KEY,
    organization_id UUID         NOT NULL,
    full_name       VARCHAR(150),
    email           VARCHAR(254),
    role            VARCHAR(30)  NOT NULL,
    active          BOOLEAN      NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

-- Copia de las asignaciones de organization (StaffAssigned, StaffUnassigned): el encargado de obra solo ve la
-- custodia de los despachos a sus obras.
CREATE TABLE assignment_projection (
    assignment_id   UUID PRIMARY KEY,
    organization_id UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    location_id     UUID        NOT NULL,
    location_type   VARCHAR(20) NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_assignment_projection_user ON assignment_projection (user_id, location_id);

-- Consumidor idempotente (kernel compartido): un evento se procesa una sola vez.
CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
