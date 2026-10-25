# language: es
Característica: US48 Exportación del reporte de conciliación
  Como administrador de la empresa
  quiero descargar el reporte de conciliación filtrado en CSV y en PDF
  para trabajarlo en Excel o compartirlo con gerencia y el contratista

  Antecedentes:
    Dado que en la organización trabajan "Ana Torres" como "administrador"
    Y que en la organización trabajan "Luis Ramos" como "encargado de obra"
    Y que "Luis Ramos" está asignado a la obra "Torre Norte"
    Y que existen la obra "Torre Norte", la obra "Edificio Sur" y el almacén "Almacén Central"
    Y que el material "CEM-001" "Cemento Portland tipo I" en "BAG" tiene tolerancia de 2 %
    Y que el material "FIE-012" "Fierro corrugado 1/2 pulgada" en "KG" tiene tolerancia de 1 %
    Y que el material "ARE-003" "Arena gruesa" en "M3" tiene tolerancia de 5 %
    Y que el despacho "MAN-20261103-0001" salió el "2026-11-03" del "Almacén Central" a "Torre Norte" con "50 CEM-001, 1200 FIE-012"
    Y que "Torre Norte" confirmó la recepción de "MAN-20261103-0001" con "47 CEM-001, 1196.5 FIE-012"
    Y que se abrió una discrepancia "SHORTAGE" de 3 de "CEM-001" en "MAN-20261103-0001"
    Y que el despacho "MAN-20261110-0002" salió el "2026-11-10" del "Almacén Central" a "Torre Norte" con "8 ARE-003, 30 CEM-001"
    Y que "Torre Norte" recibió parcialmente "MAN-20261110-0002" con "5 ARE-003"
    Y que el despacho "MAN-20261118-0003" salió el "2026-11-18" del "Almacén Central" a "Torre Norte" con "6 ARE-003"
    Y que "Torre Norte" confirmó la recepción de "MAN-20261118-0003" con "5.8 ARE-003"

  Escenario: Exportar el reporte filtrado a CSV para Excel
    Cuando "Ana Torres" exporta la conciliación de "Torre Norte" del "2026-11-01" al "2026-11-30" en "csv"
    Entonces descarga un archivo "text/csv" con extensión "csv"
    Y el CSV empieza con BOM y tiene las líneas "BuildShield | Obra;Torre Norte | Desde;01/11/2026 | Hasta;30/11/2026 | Generado;  | Torre Norte;Almacén Central;CEM-001;Cemento Portland tipo I;BAG;2;80;47;30;3;6,00;2;Supera la tolerancia;1;1"

  Escenario: Exportar el reporte filtrado a PDF
    Cuando "Ana Torres" exporta la conciliación de "Torre Norte" del "2026-11-01" al "2026-11-30" en "pdf"
    Entonces descarga un archivo "application/pdf" con extensión "pdf"
    Y el PDF dice "BuildShield | Reporte de conciliación por obra y periodo | Obra: Torre Norte | Periodo: 01/11/2026 a 30/11/2026 | Generado el | por Ana Torres | Totales por unidad | Casos pendientes"

  Escenario: Un formato no soportado se rechaza
    Cuando "Ana Torres" exporta la conciliación de "Torre Norte" del "2026-11-01" al "2026-11-30" en "xlsx"
    Entonces la respuesta tiene estado 400

  Escenario: El encargado de obra exporta solo lo de sus obras
    Cuando "Luis Ramos" exporta la conciliación de "Torre Norte" del "2026-11-01" al "2026-11-30" en "csv"
    Entonces descarga un archivo "text/csv" con extensión "csv"
    Cuando "Luis Ramos" exporta la conciliación de "Edificio Sur" del "2026-11-01" al "2026-11-30" en "csv"
    Entonces la respuesta tiene estado 403
