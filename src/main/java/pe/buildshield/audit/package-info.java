/**
 * Microservicio Trazabilidad y Auditoría (contexto de soporte): registro de solo anexado de todos los eventos de
 * integración e historial de custodia de cada despacho. Consume los seis tópicos y no publica eventos.
 *
 * <p>Capas: {@code interfaces} (REST) → {@code application} (casos de uso) → {@code domain} (Java puro);
 * {@code infrastructure} implementa los puertos (JDBC, Kafka). {@code shared} es el kernel compartido copiado de
 * buildshield-iam-service.
 */
package pe.buildshield.audit;
