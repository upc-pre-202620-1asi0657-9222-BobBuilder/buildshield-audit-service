package pe.buildshield.audit.infrastructure.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consume {@code buildshield.ordering}: pedidos, despachos y recepciones; fuente principal de la custodia. */
@Component
class OrderingEventConsumer {

    private final AuditEventRecorder recorder;

    OrderingEventConsumer(AuditEventRecorder recorder) {
        this.recorder = recorder;
    }

    @KafkaListener(topics = "${buildshield.messaging.consumes.ordering:buildshield.ordering}")
    void onMessage(ConsumerRecord<String, String> record) {
        recorder.record(record);
    }
}
