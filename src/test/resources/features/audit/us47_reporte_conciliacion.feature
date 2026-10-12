# language: es
Característica: US47 Reporte de conciliación por obra y periodo
  Como administrador de la empresa
  quiero ver por obra, almacén y periodo lo despachado, lo recibido, la merma y lo que sigue pendiente
  para saber dónde se pierde material y qué casos falta cerrar

  Antecedentes:
    Dado que en la organización trabajan "Ana Torres" como "administrador"
    Y que en la organización trabajan "Rosa Quispe" como "encargado de almacén"
    Y que en la organización trabajan "Luis Ramos" como "encargado de obra"
    Y que "Luis Ramos" está asignado a la obra "Torre Norte"
    Y que existen la obra "Torre Norte", la obra "Edificio Sur" y el almacén "Almacén Central"
    Y que el material "CEM-001" "Cemento Portland" en "BAG" tiene tolerancia de 2 %
    Y que el despacho "MAN-1" salió el "2026-11-03" del "Almacén Central" a "Torre Norte" con "50 CEM-001"
    Y que "Torre Norte" confirmó la recepción de "MAN-1" con "47 CEM-001"
    Y que se abrió una discrepancia "SHORTAGE" de 3 de "CEM-001" en "MAN-1"
    Y que el despacho "MAN-2" salió el "2026-11-10" del "Almacén Central" a "Torre Norte" con "20 CEM-001"
    Y que el despacho "MAN-3" salió el "2026-11-12" del "Almacén Central" a "Edificio Sur" con "30 CEM-001"

  Escenario: El administrador ve la merma que supera la tolerancia y los casos pendientes de una obra
    Cuando "Ana Torres" consulta la conciliación de "Torre Norte" del "2026-11-01" al "2026-11-30"
    Entonces la fila de "CEM-001" en "Torre Norte" muestra despachado "70", recibido "47", por recibir "20", merma "3" y merma "6.00" %
    Y la fila de "CEM-001" en "Torre Norte" "supera" la tolerancia
    Y hay 2 casos pendientes: "Discrepancia abierta, Recepción pendiente"

  Escenario: El encargado de obra solo ve las obras que tiene asignadas
    Cuando "Luis Ramos" consulta la conciliación del "2026-11-01" al "2026-11-30"
    Entonces el reporte tiene 2 despachos y solo de "Torre Norte"
    Cuando "Luis Ramos" consulta la conciliación de "Edificio Sur" del "2026-11-01" al "2026-11-30"
    Entonces la respuesta tiene estado 403

  Escenario: El periodo deja fuera lo que salió en otras fechas
    Cuando "Ana Torres" consulta la conciliación del "2026-11-11" al "2026-11-30"
    Entonces el reporte tiene 1 despacho y solo de "Edificio Sur"

  Escenario: El read model se reconstruye desde el registro de solo anexado
    Dado que el read model de conciliación se borró
    Cuando "Ana Torres" reconstruye el read model de conciliación
    Entonces se volvieron a aplicar 9 eventos
    Cuando "Ana Torres" consulta la conciliación de "Torre Norte" del "2026-11-01" al "2026-11-30"
    Entonces la fila de "CEM-001" en "Torre Norte" muestra despachado "70", recibido "47", por recibir "20", merma "3" y merma "6.00" %

  Escenario: Solo el administrador reconstruye el read model
    Cuando "Rosa Quispe" reconstruye el read model de conciliación
    Entonces la respuesta tiene estado 403
