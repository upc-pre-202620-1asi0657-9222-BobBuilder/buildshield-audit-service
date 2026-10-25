# Desviaciones frente a la arquitectura v4 · buildshield-audit-service

Fuente fija: `buildshield-presentacion-v4.pdf` (ER pág. 19, C4 pág. 26). Todo lo que no figura ahí y se conserva va aquí con su motivo.

## Tablas extra

| Tabla | Motivo |
|---|---|
| `user_projection` | Copia local de los usuarios de iam (`UserCreated` y `UserUpdated`). Muestra el nombre del responsable en la bitácora y en la custodia, y bloquea los tokens de usuarios desactivados o con otro rol. Sin ella habría que llamar a iam por REST. |
| `assignment_projection` | Copia local de las asignaciones de organization (`StaffAssigned` y `StaffUnassigned`). El encargado de obra solo ve la custodia de los despachos a sus obras, y el de almacén o de obra solo el reporte de conciliación de lo que tiene asignado. |

Los read models del Sprint 4 (`location_projection`, `material_projection`, `reconciliation_line` y `reconciliation_discrepancy`) **se eliminaron**: el reporte de conciliación se arma consultando `audit_log` directamente. Con 40 000 despachos tarda menos de un segundo, muy por debajo de los 10 s.

## Diferencias de definición en `audit_log`

- **`actor_user_id` no nulo**: el ER lo marca sin `N`, pero hay acciones del sistema sin usuario (por ejemplo `SubscriptionExpired`). Esas llevan el usuario cero (`00000000-0000-0000-0000-000000000000`); la API devuelve `actorId` nulo para ellas.
- **`aggregate_type` y `action`** se acortan a 20 y 40 caracteres, los máximos del ER, si el evento trae más. `aggregate_type` queda en `UNKNOWN` si el sobre no lo informa.
- **`aggregate_id`** es `uuid`. Si el sobre trae otra cosa, se deriva un uuid estable del texto para no perder el evento.
- **`before_value`** queda siempre vacío: los eventos de integración informan el estado posterior (el payload, que va en `after_value`), no el anterior.
- **Columnas que ya no existen**: `topic`, `event_type` (ahora `action`), `recorded_at` y `correlation_id`. El filtro `topic` de `GET /audit/events` se eliminó. La respuesta conserva los nombres `eventType`, `actorId` y `payload` (= `action`, `actor_user_id` y `after_value`) para no romper a los clientes, y agrega `beforeValue`, `prevHash` y `hash`.
- **Restricción extra** `UNIQUE (organization_id, prev_hash)`: garantiza una cadena sin bifurcaciones por organización.
- **`id`** es `bigserial` (antes uuid); el historial existente se migró en V3 recalculando la cadena con el orden `occurred_at`, `recorded_at`.

## Componentes

- `AuditEventConsumer` escucha los seis tópicos con un solo `@KafkaListener`; es el valor de `processed_events.consumer`.
- Clases de apoyo que se conservan: `CustodyQueryService` (línea de tiempo de custodia, US39), `CustodyExportService` y `PdfCustodyRenderer` (PDF de la custodia, RNF03), `ReconciliationQueryService`, `ReconciliationExportService`, `ReconciliationProjector` y `InMemoryReconciliationReadModel` (reporte de conciliación, US47 y US48), con sus renderers CSV y PDF.
- `AuditController` incluye `GET /audit/verify`; la custodia (`CustodyController`) y el reporte (`ReconciliationReportController`) son controladores por recurso.

## Endpoints

- Se elimina `POST /api/v1/audit/reports/reconciliation/rebuild`: ya no hay read model que reconstruir.
- Se agrega `GET /api/v1/audit/verify` (ADMINISTRATOR) y `GET /api/v1/dispatches/{id}/custody?format=pdf`. Esta última comparte la ruta de la custodia que el gateway ya envía a audit.
