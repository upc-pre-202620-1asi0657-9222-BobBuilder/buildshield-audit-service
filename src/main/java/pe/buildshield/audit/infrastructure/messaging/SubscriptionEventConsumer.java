package pe.buildshield.audit.infrastructure.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consume {@code buildshield.subscription}: activación y vencimiento de la suscripción, solo para el registro. */
@Component
class SubscriptionEventConsumer {

    private final AuditEventRecorder recorder;

    SubscriptionEventConsumer(AuditEventRecorder recorder) {
        this.recorder = recorder;
    }

    @KafkaListener(
            topics = "${buildshield.messaging.consumes.subscription:buildshield.subscription}")
    void onMessage(ConsumerRecord<String, String> record) {
        recorder.record(record);
    }
}
