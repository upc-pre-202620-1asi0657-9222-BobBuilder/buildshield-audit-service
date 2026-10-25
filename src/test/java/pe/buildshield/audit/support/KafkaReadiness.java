package pe.buildshield.audit.support;

import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Espera a que cada consumidor tenga asignadas las 3 particiones de su tópico antes de enviar eventos. Con la máquina
 * cargada el primer rebalanceo del grupo puede tardar más que la espera de un escenario, y los primeros eventos
 * quedaban sin procesar a tiempo. Solo espera la primera vez: después las asignaciones ya están.
 */
public final class KafkaReadiness {

    /** Particiones de cada tópico en {@code @EmbeddedKafka}. */
    private static final int PARTITIONS_PER_TOPIC = 3;
    /** El primer rebalanceo con la máquina muy cargada llegó a pasar el minuto. */
    private static final long TIMEOUT_MS = 180_000;

    private KafkaReadiness() {
    }

    public static void awaitAssignments(KafkaListenerEndpointRegistry registry) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            String[] topics = container.getContainerProperties().getTopics();
            int partitions = (topics == null ? 1 : topics.length) * PARTITIONS_PER_TOPIC;
            while (assigned(container) < partitions) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("El consumidor " + container.getListenerId() + " tiene "
                            + assigned(container) + " de " + partitions + " particiones");
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }
        }
    }

    private static int assigned(MessageListenerContainer container) {
        var partitions = container.getAssignedPartitions();
        return partitions == null ? 0 : partitions.size();
    }
}
