-- US47 y US48: read model del reporte de conciliación por obra, almacén y periodo. Se alimenta de los mismos eventos
-- que audit ya registra en audit_log (ordering, discrepancy y organization) y se puede reconstruir desde ese registro
-- de solo anexado (POST /api/v1/audit/reports/reconciliation/rebuild). A diferencia de audit_log, estas tablas sí se
-- actualizan: son una vista derivada, no la fuente de verdad.

-- Nombres de obras y almacenes (LocationRegistered, LocationUpdated).
CREATE TABLE location_projection (
    location_id     UUID PRIMARY KEY,
    organization_id UUID         NOT NULL,
    type            VARCHAR(20)  NOT NULL,
    name            VARCHAR(150) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_location_projection_organization ON location_projection (organization_id);

-- Catálogo de materiales (MaterialRegistered, MaterialUpdated): SKU, nombre, unidad y tolerancia de merma.
CREATE TABLE material_projection (
    material_id       UUID PRIMARY KEY,
    organization_id   UUID         NOT NULL,
    sku               VARCHAR(60),
    name              VARCHAR(150),
    unit              VARCHAR(20),
    tolerance_percent NUMERIC,
    updated_at        TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_material_projection_organization ON material_projection (organization_id);

-- Una fila por línea de despacho: lo despachado (DispatchDeparted), lo recibido hasta ahora
-- (ReceptionPartiallyReceived), la conformidad con su merma y tolerancia (ReceptionConfirmed) o la anulación
-- (DispatchCancelled). El periodo del reporte se toma de la salida del almacén (o de la conformidad si la salida
-- todavía no llegó).
CREATE TABLE reconciliation_line (
    dispatch_line_id  UUID PRIMARY KEY,
    organization_id   UUID        NOT NULL,
    dispatch_id       UUID        NOT NULL,
    order_line_id     UUID,
    manifest_code     VARCHAR(40),
    warehouse_id      UUID,
    worksite_id       UUID,
    material_id       UUID        NOT NULL,
    sku               VARCHAR(60),
    material_name     VARCHAR(150),
    unit              VARCHAR(20),
    dispatched_qty    NUMERIC     NOT NULL,
    received_qty      NUMERIC,
    shrinkage_percent NUMERIC,
    tolerance_percent NUMERIC,
    within_tolerance  BOOLEAN,
    status            VARCHAR(20) NOT NULL,
    departed_at       TIMESTAMPTZ,
    received_at       TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_reconciliation_line_status
        CHECK (status IN ('IN_TRANSIT', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED'))
);

CREATE INDEX idx_reconciliation_line_period
    ON reconciliation_line (organization_id, (COALESCE(departed_at, received_at)));
CREATE INDEX idx_reconciliation_line_dispatch ON reconciliation_line (organization_id, dispatch_id);

-- Estado vigente de cada discrepancia (DiscrepancyOpened, Responded, Escalated, Accepted, Resolved): el reporte
-- muestra las que siguen abiertas (ni aceptadas ni resueltas).
CREATE TABLE reconciliation_discrepancy (
    discrepancy_id  UUID PRIMARY KEY,
    organization_id UUID        NOT NULL,
    dispatch_id     UUID,
    manifest_code   VARCHAR(40),
    worksite_id     UUID,
    warehouse_id    UUID,
    material_id     UUID,
    sku             VARCHAR(60),
    material_name   VARCHAR(150),
    type            VARCHAR(30),
    quantity        NUMERIC,
    status          VARCHAR(20) NOT NULL,
    opened_at       TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_reconciliation_discrepancy_open
    ON reconciliation_discrepancy (organization_id, dispatch_id) WHERE status NOT IN ('ACCEPTED', 'RESOLVED');
