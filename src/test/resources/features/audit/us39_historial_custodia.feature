# language: es
Característica: US39 Consulta del historial de custodia
  Como responsable de un despacho
  quiero ver cada paso que siguió el material desde el almacén hasta la obra
  para saber quién lo tuvo, cuándo y qué pasó en el camino

  Antecedentes:
    Dado que en la organización trabajan "Ana Torres" como "administrador"
    Y que en la organización trabajan "Rosa Quispe" como "encargado de almacén"
    Y que en la organización trabajan "Luis Ramos" como "encargado de obra"
    Y que en la organización trabajan "Pedro Soto" como "encargado de obra"
    Y que "Luis Ramos" está asignado a la obra "Torre Norte"
    Y que "Rosa Quispe" preparó el despacho "D1" con manifiesto "MAN-20261103-0001" hacia la obra "Torre Norte"
    Y que "Rosa Quispe" registró el evento "DispatchDeparted" del despacho "D1" hacia la obra "Torre Norte"
    Y que "Rosa Quispe" registró el evento "TransitIncidentReported" del despacho "D1" hacia la obra "Torre Norte"
    Y que "Luis Ramos" registró el evento "ReceptionConfirmed" del despacho "D1" hacia la obra "Torre Norte"
    Y que "Luis Ramos" abrió una discrepancia del despacho "D1" por "Faltan 3 bolsas"

  Escenario: El encargado de obra ve la custodia completa de un despacho a su obra
    Cuando "Luis Ramos" consulta el historial de custodia del despacho "D1"
    Entonces ve el manifiesto "MAN-20261103-0001" y los pasos "Despacho preparado, Salida a la obra, Incidencia en ruta, Recepción confirmada, Discrepancia abierta" en ese orden
    Y el paso "Despacho preparado" lo hizo "Rosa Quispe" con el detalle "Manifiesto MAN-20261103-0001, 1 línea"
    Y el paso "Incidencia en ruta" lo hizo "Rosa Quispe" con el detalle "Retraso: Tráfico en la Panamericana"
    Y el paso "Recepción confirmada" lo hizo "Luis Ramos" con el detalle "1 de 1 materiales fuera de la tolerancia"
    Y el paso "Discrepancia abierta" lo hizo "Luis Ramos" con el detalle "Cemento Portland: Faltan 3 bolsas"

  Escenario: El encargado de almacén y el administrador también la consultan
    Cuando "Rosa Quispe" consulta el historial de custodia del despacho "D1"
    Entonces la respuesta tiene estado 200
    Cuando "Ana Torres" consulta el historial de custodia del despacho "D1"
    Entonces la respuesta tiene estado 200

  Escenario: Un encargado de obra no ve la custodia de despachos a obras que no tiene asignadas
    Cuando "Pedro Soto" consulta el historial de custodia del despacho "D1"
    Entonces la respuesta tiene estado 404

  Escenario: Un evento entregado más de una vez aparece una sola vez en la custodia
    Dado que el evento de salida del despacho "D1" llegó dos veces más
    Cuando "Ana Torres" consulta el historial de custodia del despacho "D1"
    Entonces ve el manifiesto "MAN-20261103-0001" y los pasos "Despacho preparado, Salida a la obra, Incidencia en ruta, Recepción confirmada, Discrepancia abierta" en ese orden
