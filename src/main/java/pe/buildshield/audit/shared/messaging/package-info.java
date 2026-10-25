/**
 * Eventos de integración entre microservicios: sobre JSON común ({@link pe.buildshield.audit.shared.messaging.EventEnvelope}),
 * Transactional Outbox ({@link pe.buildshield.audit.shared.messaging.OutboxWriter} y
 * {@link pe.buildshield.audit.shared.messaging.OutboxPublisher}) y consumo idempotente
 * ({@link pe.buildshield.audit.shared.messaging.IdempotentEventProcessor}). Entrega al menos una vez.
 */
package pe.buildshield.audit.shared.messaging;
