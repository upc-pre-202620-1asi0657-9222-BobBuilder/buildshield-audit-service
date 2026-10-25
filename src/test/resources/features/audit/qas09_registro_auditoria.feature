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

  Escenario: Cada entrada del registro queda encadenada a la anterior con una huella SHA-256
    Cuando "Ana Torres" consulta el registro de auditoría
    Entonces cada entrada del registro está encadenada a la anterior

  Escenario: La verificación de la cadena confirma que ninguna entrada fue alterada
    Cuando "Ana Torres" verifica la cadena de huellas del registro de auditoría
    Entonces la cadena es válida y revisó 4 entradas

  Escenario: La verificación delata una entrada alterada por fuera de la aplicación
    Dado que alguien altera directamente la entrada 2 del registro de auditoría
    Cuando "Ana Torres" verifica la cadena de huellas del registro de auditoría
    Entonces la cadena está rota en la entrada 2

  Escenario: Solo el administrador verifica la cadena
    Cuando "Rosa Quispe" verifica la cadena de huellas del registro de auditoría
    Entonces la respuesta tiene estado 403
