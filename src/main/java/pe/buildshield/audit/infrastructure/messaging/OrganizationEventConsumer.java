package pe.buildshield.audit.infrastructure.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consume {@code buildshield.organization}: registra todo; las asignaciones también actualizan su copia local. */
@Component
class OrganizationEventConsumer {

    private final AuditEventRecorder recorder;

    OrganizationEventConsumer(AuditEventRecorder recorder) {
        this.recorder = recorder;
    }

    @KafkaListener(
            topics = "${buildshield.messaging.consumes.organization:buildshield.organization}")
    void onMessage(ConsumerRecord<String, String> record) {
        recorder.record(record);
    }
}
