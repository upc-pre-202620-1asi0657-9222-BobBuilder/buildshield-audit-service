package pe.buildshield.audit.interfaces.rest;

/** Ejemplos de respuesta y de parámetros para la documentación OpenAPI de audit-service. */
final class AuditApiExamples {

    static final String DISPATCH_ID = "6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c";
    static final String USER_ID = "8a9b0c1d-2e3f-4a5b-8c6d-7e8f9a0b1c2d";

    static final String EVENTS = """
            {
              "items": [
                {
                  "id": "0b1c2d3e-4f5a-4b6c-8d7e-8f9a0b1c2d3e",
                  "eventId": "9c8b7a6f-5e4d-4c3b-8a2f-1e0d9c8b7a6f",
                  "topic": "buildshield.ordering",
                  "eventType": "DispatchDeparted",
                  "aggregateType": "DISPATCH",
                  "aggregateId": "6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c",
                  "actorId": "8a9b0c1d-2e3f-4a5b-8c6d-7e8f9a0b1c2d",
                  "actorName": "Rosa Quispe",
                  "occurredAt": "2026-11-03T13:30:00Z",
                  "recordedAt": "2026-11-03T13:30:01Z",
                  "payload": {"dispatchId": "6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c", "departedAt": "2026-11-03T13:30:00Z"}
                }
              ],
              "page": 0,
              "size": 50,
              "total": 1
            }""";

    static final String CUSTODY = """
            {
              "dispatchId": "6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c",
              "manifestCode": "MAN-20261103-0001",
              "events": [
                {
                  "at": "2026-11-03T12:00:00Z",
                  "type": "PREPARED",
                  "label": "Despacho preparado",
                  "actorId": "8a9b0c1d-2e3f-4a5b-8c6d-7e8f9a0b1c2d",
                  "actorName": "Rosa Quispe",
                  "detail": "Manifiesto MAN-20261103-0001, 2 líneas"
                },
                {
                  "at": "2026-11-03T13:30:00Z",
                  "type": "DEPARTED",
                  "label": "Salida a la obra",
                  "actorId": "8a9b0c1d-2e3f-4a5b-8c6d-7e8f9a0b1c2d",
                  "actorName": "Rosa Quispe",
                  "detail": "2 materiales despachados"
                },
                {
                  "at": "2026-11-03T16:10:00Z",
                  "type": "RECEIVED",
                  "label": "Recepción confirmada",
                  "actorId": "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f",
                  "actorName": "Luis Ramos",
                  "detail": "1 de 2 materiales fuera de la tolerancia"
                }
              ]
            }""";

    private AuditApiExamples() {
    }
}
