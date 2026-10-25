package pe.buildshield.audit.interfaces.rest;

/** Ejemplos de la documentación OpenAPI del reporte de conciliación (US47 y US48). */
final class ReconciliationApiExamples {

    static final String WORKSITE_ID = "3a4b5c6d-7e8f-4a9b-8c0d-1e2f3a4b5c6d";
    static final String WAREHOUSE_ID = "4b5c6d7e-8f9a-4b0c-9d1e-2f3a4b5c6d7e";

    static final String REPORT = """
            {
              "filters": {
                "worksiteId": "3a4b5c6d-7e8f-4a9b-8c0d-1e2f3a4b5c6d",
                "worksiteName": "Torre Norte",
                "warehouseId": null,
                "warehouseName": null,
                "from": "2026-11-01",
                "to": "2026-11-30"
              },
              "generatedAt": "2026-12-01T14:05:00Z",
              "generatedBy": "Ana Torres",
              "rows": [
                {
                  "worksiteId": "3a4b5c6d-7e8f-4a9b-8c0d-1e2f3a4b5c6d",
                  "worksiteName": "Torre Norte",
                  "warehouseId": "4b5c6d7e-8f9a-4b0c-9d1e-2f3a4b5c6d7e",
                  "warehouseName": "Almacén Central",
                  "materialId": "5c6d7e8f-9a0b-4c1d-8e2f-3a4b5c6d7e8f",
                  "sku": "CEM-001",
                  "materialName": "Cemento Portland tipo I",
                  "unit": "BAG",
                  "dispatchCount": 2,
                  "dispatched": 100,
                  "received": 94,
                  "pendingReception": 0,
                  "confirmedDispatched": 100,
                  "shrinkage": 6,
                  "shrinkagePercent": 6.00,
                  "tolerancePercent": 2,
                  "toleranceStatus": "EXCEEDED",
                  "linesOutOfTolerance": 1,
                  "openDiscrepancies": 1
                }
              ],
              "totalsByUnit": [
                {"unit": "BAG", "dispatched": 100, "received": 94, "pendingReception": 0, "shrinkage": 6,
                 "shrinkagePercent": 6.00}
              ],
              "summary": {
                "dispatchCount": 2,
                "pendingReceptions": 0,
                "materialRows": 1,
                "rowsExceedingTolerance": 1,
                "linesOutOfTolerance": 1,
                "openDiscrepancies": 1
              },
              "pendingCases": [
                {
                  "type": "OPEN_DISCREPANCY",
                  "dispatchId": "6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c",
                  "manifestCode": "MAN-20261103-0001",
                  "discrepancyId": "7a8b9c0d-1e2f-4a3b-9c4d-5e6f7a8b9c0d",
                  "worksiteName": "Torre Norte",
                  "warehouseName": "Almacén Central",
                  "material": "CEM-001 Cemento Portland tipo I",
                  "status": "Abierta",
                  "since": "2026-11-03T16:12:00Z",
                  "detail": "Faltante 6"
                }
              ]
            }""";

    private ReconciliationApiExamples() {
    }
}
