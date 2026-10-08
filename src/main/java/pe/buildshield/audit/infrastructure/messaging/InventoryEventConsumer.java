package pe.buildshield.audit.infrastructure.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consume {@code buildshield.inventory}: reservas, rechazos y movimientos de stock, solo para el registro. */
@Component
class InventoryEventConsumer {

    private final AuditEventRecorder recorder;

    InventoryEventConsumer(AuditEventRecorder recorder) {
        this.recorder = recorder;
    }

    @KafkaListener(topics = "${buildshield.messaging.consumes.inventory:buildshield.inventory}")
    void onMessage(ConsumerRecord<String, String> record) {
        recorder.record(record);
    }
}
