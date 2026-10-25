package pe.buildshield.audit.infrastructure.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import pe.buildshield.audit.application.AuditRecorder;
import pe.buildshield.audit.shared.messaging.IdempotentEventProcessor;

/**
 * Consumidor Kafka de audit (componente Audit Event Consumer del C4): escucha los seis tópicos de los servicios que
 * publican (iam, organization, inventory, ordering, discrepancy y subscription) y entrega cada evento, de cualquier
 * tipo, a {@link AuditRecorder}. Cada evento se registra una sola vez: {@link IdempotentEventProcessor}
 * ({@code processed_events}) y además {@code event_id} único en {@code audit_log}.
 */
@Component
public class AuditEventConsumer {

    /** Valor de {@code processed_events.consumer}. */
    public static final String CONSUMER = "AuditEventConsumer";

    private final IdempotentEventProcessor processor;
    private final AuditRecorder recorder;

    public AuditEventConsumer(IdempotentEventProcessor processor, AuditRecorder recorder) {
        this.processor = processor;
        this.recorder = recorder;
    }

    @KafkaListener(topics = {
            "${buildshield.messaging.consumes.iam:buildshield.iam}",
            "${buildshield.messaging.consumes.organization:buildshield.organization}",
            "${buildshield.messaging.consumes.inventory:buildshield.inventory}",
            "${buildshield.messaging.consumes.ordering:buildshield.ordering}",
            "${buildshield.messaging.consumes.discrepancy:buildshield.discrepancy}",
            "${buildshield.messaging.consumes.subscription:buildshield.subscription}"})
    public void onMessage(ConsumerRecord<String, String> record) {
        processor.process(record, CONSUMER, recorder::record);
    }
}
