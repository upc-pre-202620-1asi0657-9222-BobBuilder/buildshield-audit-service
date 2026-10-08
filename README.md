# buildshield-audit-service

Trazabilidad y Auditoría de BuildShield (BobBuilder): registro de solo anexado de todos los eventos de integración e
historial de custodia de cada despacho. Contrato de la arquitectura: `wiki/informe-tf/plan-microservicios.md` del
proyecto.

| Dato | Valor |
|---|---|
| Puerto | 8088 |
| Base | `audit_db` (PostgreSQL 16+) |
| Paquete | `pe.buildshield.audit` (kernel compartido copiado de iam en `pe.buildshield.audit.shared`) |
| Publica | nada (sin outbox ni tópico propio) |
| Consume | los seis tópicos: `buildshield.iam`, `buildshield.organization`, `buildshield.inventory`, `buildshield.ordering`, `buildshield.discrepancy` y `buildshield.subscription` (un `<X>EventConsumer` por tópico) |

## Registro de solo anexado

- `audit_log` guarda cada evento con su sobre (tópico, tipo, agregado, organización, actor, fecha, correlación) y el
  payload sin cambios. `event_id` es único: un evento entregado dos veces queda una sola vez (además de
  `processed_events`). Los tipos desconocidos también se registran.
- Un trigger (como V14 del Core) rechaza `UPDATE`, `DELETE` y `TRUNCATE` con SQLSTATE `42501` (QAS05 y QAS09).
- Copias locales: `user_projection` (de `UserCreated` y `UserUpdated`: nombre del responsable y bloqueo de tokens de
  usuarios desactivados) y `assignment_projection` (de `StaffAssigned` y `StaffUnassigned`: qué obras ve cada encargado
  de obra).

## Endpoints

- `GET /api/v1/audit/events` (ADMINISTRATOR): eventos de la organización del más reciente al más antiguo, con filtros
  `eventType`, `aggregateType`, `aggregateId`, `actorId`, `topic`, `from`, `to` y página (`page`, `size` hasta 200).
  Respuesta `{"items":[...],"page","size","total"}`.
- `GET /api/v1/dispatches/{id}/custody` (los tres roles; el encargado de obra solo de despachos a sus obras): línea de
  tiempo `{"dispatchId","manifestCode","events":[{"at","type","label","actorId","actorName","detail"}]}` ordenada por
  `at`, armada con los eventos de ordering y discrepancy del despacho. Tipos: `PREPARED`, `CARRIER_ASSIGNED`,
  `DEPARTURE_WEIGHED`, `DEPARTED`, `INCIDENT`, `CANCELLED`, `RECEPTION_OPENED`, `ARRIVAL_WEIGHED`,
  `PARTIALLY_RECEIVED`, `RECEIVED`, `DISCREPANCY_OPENED`, `DISCREPANCY_RESPONDED`, `DISCREPANCY_ESCALATED`,
  `DISCREPANCY_RESOLVED`, `DISCREPANCY_ACCEPTED`. Sin eventos o sin permiso: 404. El gateway enruta esta ruta a audit
  antes que la regla general de `/dispatches`.
- OpenAPI en `/api/v1/api-docs` y `/swagger-ui.html` (perfiles local y test).

## Variables de entorno

`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS` (por defecto `localhost:9092`) y
`BUILDSHIELD_JWT_PUBLIC_KEY`. Perfiles `local`, `test` y `prod`.

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
cubre US39 (historial de custodia) y QAS09 (registro de auditoría). JaCoCo exige 80 % de líneas en `domain` y
`application`.
