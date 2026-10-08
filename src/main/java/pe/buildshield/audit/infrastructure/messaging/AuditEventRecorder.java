package pe.buildshield.audit.infrastructure.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;
import pe.buildshield.audit.application.AuditCommandService;
import pe.buildshield.audit.shared.messaging.IdempotentEventProcessor;

/**
 * Paso común de los consumidores de audit: cada evento, de cualquier tipo, se registra una sola vez con el tópico
 * del que llegó ({@link IdempotentEventProcessor} y además {@code event_id} único en {@code audit_log}).
 */
@Component
class AuditEventRecorder {

    private final IdempotentEventProcessor processor;
    private final AuditCommandService commands;

    AuditEventRecorder(IdempotentEventProcessor processor, AuditCommandService commands) {
        this.processor = processor;
        this.commands = commands;
    }

    boolean record(ConsumerRecord<String, String> record) {
        return processor.process(record, event -> commands.record(record.topic(), event));
    }
}
