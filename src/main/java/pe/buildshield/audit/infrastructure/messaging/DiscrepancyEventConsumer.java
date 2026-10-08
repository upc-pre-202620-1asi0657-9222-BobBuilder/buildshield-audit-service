package pe.buildshield.audit.infrastructure.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consume {@code buildshield.discrepancy}: apertura, respuesta, escalamiento y cierre, parte de la custodia. */
@Component
class DiscrepancyEventConsumer {

    private final AuditEventRecorder recorder;

    DiscrepancyEventConsumer(AuditEventRecorder recorder) {
        this.recorder = recorder;
    }

    @KafkaListener(
            topics = "${buildshield.messaging.consumes.discrepancy:buildshield.discrepancy}")
    void onMessage(ConsumerRecord<String, String> record) {
        recorder.record(record);
    }
}
