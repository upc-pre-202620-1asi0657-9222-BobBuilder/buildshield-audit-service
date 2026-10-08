# language: es
Característica: QAS09 Auditabilidad: registro de solo anexado de los eventos
  Como administrador de la empresa
  quiero consultar quién hizo qué y cuándo en toda la organización
  para responder ante una auditoría o un reclamo

  Antecedentes:
    Dado que en la organización trabajan "Ana Torres" como "administrador"
    Y que en la organización trabajan "Rosa Quispe" como "encargado de almacén"
    Y que "Rosa Quispe" preparó el despacho "D1" con manifiesto "MAN-1" hacia la obra "Torre Norte"
    Y que "Rosa Quispe" registró el evento "DispatchDeparted" del despacho "D1" hacia la obra "Torre Norte"

  Escenario: El administrador filtra el registro por tipo de evento
    Cuando "Ana Torres" consulta el registro de auditoría del tipo "DispatchDeparted"
    Entonces ve 1 evento del tipo "DispatchDeparted"

  Escenario: El registro muestra todos los eventos de la organización
    Cuando "Ana Torres" consulta el registro de auditoría
    Entonces ve 4 eventos en total, del más reciente al más antiguo

  Escenario: Solo el administrador consulta el registro
    Cuando "Rosa Quispe" consulta el registro de auditoría
    Entonces la respuesta tiene estado 403
