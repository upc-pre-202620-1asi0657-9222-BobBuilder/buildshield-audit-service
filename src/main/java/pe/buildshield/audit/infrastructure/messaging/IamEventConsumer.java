package pe.buildshield.audit.infrastructure.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consume {@code buildshield.iam}: registra todo; {@code UserCreated} y {@code UserUpdated} también actualizan la copia de usuarios. */
@Component
class IamEventConsumer {

    private final AuditEventRecorder recorder;

    IamEventConsumer(AuditEventRecorder recorder) {
        this.recorder = recorder;
    }

    @KafkaListener(topics = "${buildshield.messaging.consumes.iam:buildshield.iam}")
    void onMessage(ConsumerRecord<String, String> record) {
        recorder.record(record);
    }
}
