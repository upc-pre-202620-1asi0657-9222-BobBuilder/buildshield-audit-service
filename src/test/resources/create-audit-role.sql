-- Rol de ejecución del servicio, como en buildshield-platform: V3 le deja solo INSERT y SELECT sobre audit_log.
CREATE ROLE audit_service NOLOGIN;
