# Cierre 11.3 — Consistencia transaccional y validación de estados

**Base confirmada:** `development` SHA `09f3ef2d7fed6457d2a72ef8cf7addfa1b110efd` (PR #47).

## Fallos detectados en el código

1. `ViajeService.cancelar` usaba lectura sin bloqueo mientras `SolicitudService.aceptar` sí bloqueaba la fila. Podía competir por el mismo viaje y dejar estados/cupos incoherentes.
2. La creación de solicitudes (`SolicitudService.crear`) no tomaba bloqueo y comprobaba solo fecha pasada, no salida de hoy a una hora anterior; tampoco impedía explícitamente solicitudes en ruta ELIMINADA.
3. `ViajeService` confirmar, iniciar, completar, reprogramar y eliminar no tomaban el mismo bloqueo en sus escrituras. Se necesita serialización a nivel del viaje.
4. Cancelar/rechazar solicitudes podía competir con su aceptación, porque se leía el estado antes de bloquear el viaje.

## Solución

- `ViajeRepository.bloquearParaAceptar`: **se conserva** para aceptar, crear, cancelar, rechazar y el bloqueo por ID de viaje. Se limpia un import duplicado previo.
- `ViajeRepository.bloquearParaConductor`: nueva consulta de escritura protegida por propietario con `PESSIMISTIC_WRITE` para transiciones del viaje. El método de lectura `findByIdAndRutaConductorId` NO cambia, para no romper los servicios readOnly.
- `SolicitudRepository.buscarViajeIdParaCancelar`: obtiene solo el ID de viaje tras validar propiedad del pasajero; después se bloquea la fila y se relee la solicitud.
- `SolicitudService.crear`: validación de ruta no eliminada, fecha+hora futura, bloqueo para serializar solicitudes repetidas y cancelación.
- `SolicitudService.aceptar`, `rechazar` y `rechazarMultiple`: usan el mismo orden de bloqueo. Rechazo múltiple bloquea viajes por ID ordenado antes de mutar solicitudes.
- `ViajeService`: todas las transiciones de viaje que modifican BD toman bloqueo; cancelación continúa permitida para conductor o pasajero aceptado, conforme US18.
- No se cambian endpoints, enums, entidades ni esquema SQL; los cambios en repositorios son métodos/consultas.

## Validaciones en IntelliJ

```powershell
.\mvnw.cmd "-Dtest=EstadosConcurrentesViaje113Test,EstadosConcurrentesSolicitud113Test,CancelacionAceptacionConcurrencia113IntegrationTest,ViajeServiceTest,ViajeEdicionServiceTest,ViajeNotificacionUs25Test,SolicitudNotificacionUs25Test,IntegridadEliminacionViajesServiceTest,AceptacionConcurrenciaCuposServiceTest,AceptacionConcurrenciaCuposIntegrationTest" test
.\mvnw.cmd test
```

### Pruebas manuales en Swagger

1. Crear viaje para mañana y dos solicitudes pendientes. Aceptar una, cancelar el viaje y tratar de aceptar la segunda. La aceptación después de cancelar debe devolver 409 y NO incrementar pasajeros.
2. Crear viaje hoy con hora anterior a la actual. Intentar enviar solicitud: debe dar 409, sin registrar solicitud.
3. Eliminar lógicamente una ruta y tratar de solicitar su viaje futuro: 409, sin solicitudes nuevas.
4. Repetir `PUT /api/viajes/{id}/cancelar` con conductor. Debe responder 200 indicando que ya estaba cancelado; no crear otra penalidad.
5. Como otro conductor, intentar `PUT /api/viajes/{id}/confirmar`, `/iniciar`, `/completar` o `DELETE`: 404, sin cambiar registros.
6. Con un pasajero sin solicitud aceptada, intentar cancelar viaje ajeno: 403.
7. Para carrera real, ejecutar la prueba de integración 11.2 y comprobar que solo se acepta la cantidad permitida. H2 permite ensayar el flujo, pero se recomienda prueba con PostgreSQL si se va a desplegar con concurrencia real.

## Precauciones

- El nombre de método `bloquearParaAceptar` se conserva por compatibilidad con US11.2 aunque ahora también se usa en otros flujos.
- `@Transactional` es obligatorio en los llamadores de los repositorios bloqueantes (ya presente en métodos afectados).
- Los tests HTTP y E2E no han sido ejecutados en esta entrega; verificar localmente.
- No incluye nuevos campos ni cambio de modelo de datos. Ninguna migración SQL.
- `git diff --check`, pruebas específicas y `mvnw test` deben ser verdes antes del PR.
