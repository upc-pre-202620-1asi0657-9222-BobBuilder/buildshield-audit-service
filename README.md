# buildshield-audit-service

Trazabilidad y Auditoría de BuildShield (BobBuilder): registro de solo anexado de todos los eventos de integración e
historial de custodia de cada despacho, y reporte de conciliación por obra y periodo con exportación a CSV y PDF
(US47 y US48). Contrato de la arquitectura: `wiki/informe-tf/plan-microservicios.md` del
proyecto.

| Dato | Valor |
|---|---|
| Puerto | 8088 |
| Base | `audit_db` (PostgreSQL 16+) |
| Paquete | `pe.buildshield.audit` (kernel compartido copiado de iam en `pe.buildshield.audit.shared`) |
| Publica | nada (sin outbox ni tópico propio) |
| Consume | los seis tópicos: `buildshield.iam`, `buildshield.organization`, `buildshield.inventory`, `buildshield.ordering`, `buildshield.discrepancy` y `buildshield.subscription`, con un solo `AuditEventConsumer` |

## Registro de solo anexado

- `audit_log` (ER v4) guarda cada acción: `event_id`, `organization_id`, `aggregate_type`, `aggregate_id`, `action` (el
  tipo del evento), `actor_user_id` (el usuario cero si la acción la hizo el sistema), `occurred_at`, `before_value`
  (vacío: los eventos no informan el estado anterior), `after_value` (el payload sin cambios) y la cadena de huellas
  `prev_hash` y `hash`. `event_id` es único: un evento entregado dos veces queda una sola vez (además de
  `processed_events`, con llave `(event_id, consumer)`). Los tipos desconocidos también se registran.
- Solo anexado: un trigger rechaza `UPDATE`, `DELETE` y `TRUNCATE` con SQLSTATE `42501` y el rol `audit_service` solo
  tiene `INSERT` y `SELECT` sobre `audit_log` (QAS05, QAS09).
- Cadena de huellas (RNF15): `hash = SHA-256(prev_hash || event_id || organization_id || aggregate_type || aggregate_id
  || action || actor_user_id || occurred_at || after_value)`, con `prev_hash` igual al `hash` de la entrada anterior de
  la misma organización y 64 ceros en la primera. Los valores entran como texto (uuid en minúsculas, `occurred_at` en
  UTC con 6 decimales y `after_value` como `jsonb::text`). Anexar toma un candado por organización
  (`pg_advisory_xact_lock`), así que dos eventos simultáneos no bifurcan la cadena, y `(organization_id, prev_hash)` es
  único. `GET /api/v1/audit/verify` recorre la cadena y recalcula cada huella.
- Copias locales: `user_projection` (de `UserCreated` y `UserUpdated`: nombre del responsable y bloqueo de tokens de
  usuarios desactivados) y `assignment_projection` (de `StaffAssigned` y `StaffUnassigned`: qué obras ve cada encargado
  de obra).

## Reporte de conciliación (US47 y US48)

- El reporte se arma al consultar, desde `audit_log`: se aplican en orden, sobre una copia en memoria, los eventos de
  despachos, recepciones, discrepancias, obras, almacenes y materiales de la organización (`DispatchDeparted` crea cada
  línea de despacho, `ReceptionPartiallyReceived` actualiza lo recibido por `orderLineId`, `ReceptionConfirmed` fija lo
  recibido, la merma y la tolerancia de ordering, `DispatchCancelled` la saca del reporte, y los cinco eventos de
  discrepancia dejan su estado vigente). No hay tablas de read model que mantener ni reconstruir. Con 40 000 despachos
  (80 000 líneas) el reporte tarda menos de un segundo (`AuditPerformanceIT`).
- Fila del reporte = obra, almacén y material: despachos, despachado, recibido, por recibir, merma (despachado menos
  recibido de las líneas conformes), merma % (sobre lo despachado de esas líneas, 2 decimales, la fórmula de ordering),
  tolerancia del material y estado (`WITHIN`, `EXCEEDED`, `NOT_RECEIVED`), líneas fuera de tolerancia y discrepancias
  abiertas. Totales por unidad (no se suman unidades distintas) y casos pendientes (despachos sin conformidad y
  discrepancias abiertas, del más antiguo al más reciente).
- Periodo: días calendario en `America/Lima` (`buildshield.reports.zone`), ambos extremos incluidos, comparados con la
  salida del almacén (o con la conformidad si la salida no llegó). Sin `from` ni `to` no hay límite.
- Permisos: administrador, toda la organización; encargado de almacén, solo sus almacenes; encargado de obra, solo sus
  obras (de `assignment_projection`). Filtrar fuera de lo asignado: 403 `REPORT_SCOPE_FORBIDDEN`.
- CSV: UTF-8 con BOM, separador `;` y coma decimal (Excel en español con coma decimal); para punto decimal,
  `BUILDSHIELD_REPORTS_CSV_SEPARATOR=,` y `BUILDSHIELD_REPORTS_CSV_DECIMAL_SEPARATOR=.`. PDF con OpenPDF (A4
  horizontal). Ambos con encabezado BuildShield, filtros y fecha de generación.

## Endpoints

- `GET /api/v1/audit/events` (ADMINISTRATOR): eventos de la organización del más reciente al más antiguo, con filtros
  `eventType` (la acción), `aggregateType`, `aggregateId`, `actorId`, `from`, `to` y página (`page`, `size` hasta 200).
  Respuesta `{"items":[{"id","eventId","eventType","aggregateType","aggregateId","actorId","actorName",
  "occurredAt","beforeValue","payload","prevHash","hash"}],"page","size","total"}`.
- `GET /api/v1/audit/verify` (ADMINISTRATOR) → `{"valid","entriesChecked","brokenEntryId","reason"}`: recorre la cadena de la
  organización en el orden en que se anexó y delata la primera entrada alterada, quitada o cambiada de lugar.
- `GET /api/v1/dispatches/{id}/custody` (los tres roles; el encargado de obra solo de despachos a sus obras): línea de
  tiempo `{"dispatchId","manifestCode","events":[{"at","type","label","actorId","actorName","detail"}]}` ordenada por
  `at`, armada con los eventos de ordering y discrepancy del despacho. Tipos: `PREPARED`, `CARRIER_ASSIGNED`,
  `DEPARTURE_WEIGHED`, `DEPARTED`, `INCIDENT`, `CANCELLED`, `RECEPTION_OPENED`, `ARRIVAL_WEIGHED`,
  `PARTIALLY_RECEIVED`, `RECEIVED`, `DISCREPANCY_OPENED`, `DISCREPANCY_RESPONDED`, `DISCREPANCY_ESCALATED`,
  `DISCREPANCY_RESOLVED`, `DISCREPANCY_ACCEPTED`. Sin eventos o sin permiso: 404. El gateway enruta esta ruta a audit
  antes que la regla general de `/dispatches`.
- `GET /api/v1/audit/reports/reconciliation?worksiteId&warehouseId&from=AAAA-MM-DD&to=AAAA-MM-DD` (los tres roles,
  con el alcance de arriba) → `{"filters","generatedAt","generatedBy","rows":[...],"totalsByUnit":[...],"summary",
  "pendingCases":[...]}`. 400 `INVALID_PERIOD` si `from` es posterior a `to`.
- `GET /api/v1/audit/reports/reconciliation/export?format=csv|pdf&...` (mismos filtros y permisos) → archivo
  `conciliacion-AAAAMMDD-HHMM.csv` o `.pdf` (`Content-Disposition: attachment`). 400 `UNSUPPORTED_FORMAT`.
- `GET /api/v1/dispatches/{id}/custody?format=pdf` (mismos permisos) → PDF `custodia-MANIFIESTO-AAAAMMDD-HHMM.pdf` con la
  línea de tiempo de custodia (RNF03: menos de 10 s; con 300 pasos tarda unos 200 ms).
- El gateway ya enruta `/api/v1/audit/**` a este servicio: no hacen falta rutas nuevas.
- OpenAPI en `/api/v1/api-docs` y `/swagger-ui.html` (perfiles local y test).

## Variables de entorno

`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS` (por defecto `localhost:9092`),
`BUILDSHIELD_JWT_PUBLIC_KEY`, `BUILDSHIELD_REPORTS_ZONE` (por defecto `America/Lima`),
`BUILDSHIELD_REPORTS_CSV_SEPARATOR` (`;`) y `BUILDSHIELD_REPORTS_CSV_DECIMAL_SEPARATOR` (`,`). Perfiles `local`, `test` y `prod`.

## Ejecutar en local (clúster PostgreSQL 127.0.0.1:5433 con autenticación trust)

```bash
mvn -B package -DskipTests
BUILDSHIELD_JWT_PUBLIC_KEY="$(cat ~/.buildshield/jwt-public.pem)" \
java -jar target/buildshield-audit-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

## Pruebas

```bash
mvn -B verify -Dbuildshield.test.external-database=true
```

Las `*IT` usan la base temporal `audit_it` en `127.0.0.1:5433` con el usuario `buildshield_core` (superusuario: la
limpieza entre pruebas desactiva el trigger solo en su sesión con `session_replication_role = replica`) y Kafka
embebido con los seis tópicos. `AuditLogImmutabilityIT` prueba que la base rechaza UPDATE, DELETE y TRUNCATE. Cucumber
cubre US39 (historial de custodia), QAS09 (registro de auditoría), US47 (reporte de conciliación) y US48
(exportación); los escenarios de US48 dejan un CSV y un PDF reales en `target/report-samples/`. JaCoCo exige 80 % de líneas en `domain` y
`application`.
