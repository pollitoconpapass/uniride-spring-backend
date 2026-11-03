# Cierre 11.2 — Concurrencia de cupos (UniRide)

## Objetivo
Evitar que dos aceptaciones simultáneas del mismo viaje sobrepasen `ruta.capacidadMaxima`, o que una misma solicitud aceptada dos veces incremente cupos o cree dos acuerdos.

## Qué cambió

- `SolicitudRepository.buscarViajeIdParaAceptar`: obtiene únicamente el ID del viaje y verifica el conductor sin materializar todavía la entidad `Viaje`.
- `ViajeRepository.bloquearParaAceptar`: consulta con `@Lock(PESSIMISTIC_WRITE)`; en PostgreSQL mantiene un bloqueo de fila durante la transacción.
- `SolicitudService.aceptar`: bloquea el viaje **antes** de volver a leer la solicitud y comprobar su estado/cupos.
- Se conserva `@Transactional`, la autorización por conductor y el retorno HTTP 409 para solicitudes procesadas o sin cupos.
- El resto de endpoints, DTOs, notificaciones y reglas de compensación permanece sin cambios.

## Verificación automatizada

```powershell
.\mvnw.cmd "-Dtest=AceptacionConcurrenciaCuposServiceTest,AceptacionConcurrenciaCuposIntegrationTest,SolicitudNotificacionUs25Test" test
.\mvnw.cmd test
```

El test de integración usa H2 con **dos transacciones reales y dos hilos** para cubrir:

1. Dos solicitudes diferentes intentan ocupar el último cupo: solo una se acepta, `viajes.pasajeros` queda en 1 y se crea un solo acuerdo.
2. Dos peticiones aceptan la misma solicitud: una se procesa y la otra recibe conflicto, sin cupo ni acuerdo duplicados.

## Validación de negocio en Swagger

1. Iniciar sesión como conductor y crear ruta con `capacidadMaxima=1` y viaje futuro `PROGRAMADO`.
2. Con dos pasajeros diferentes enviar solicitudes a ese viaje (`POST /api/solicitudes/viajes/{viajeId}`).
3. Con el conductor aceptar la primera solicitud (`PUT /api/solicitudes/{id}/aceptar`) y verificar HTTP 200.
4. Intentar aceptar la segunda y verificar **409 Conflict**, mensaje de falta de cupos.
5. Repetir la aceptación de la primera solicitud y verificar **409 Conflict**; el contador y los acuerdos no se duplican.
6. Verificar con PostgreSQL:

```sql
SELECT id, ruta_id, pasajeros, estado FROM viajes WHERE id = <ID_VIAJE>;
SELECT id, viaje_id, pasajero_id, estado FROM solicitudes WHERE viaje_id = <ID_VIAJE>;
SELECT a.id, a.solicitud_id, a.estado FROM acuerdos_compensacion a
JOIN solicitudes s ON s.id = a.solicitud_id WHERE s.viaje_id = <ID_VIAJE>;
```

La prueba de concurrencia real NO puede simularse enviando peticiones secuencialmente por Swagger; para eso está `AceptacionConcurrenciaCuposIntegrationTest`.

## Notas de alcance

- No hay migraciones SQL ni cambios de endpoints.
- Se usa bloqueo de BD, no `synchronized` de Java: sirve aunque el sistema ejecute varias instancias.
- El bloqueo cubre **aceptaciones concurrentes**. Para cobertura completa de todas las transiciones, revisar en 11.3 la carrera entre aceptar y cancelar/eliminar/iniciar un viaje.
- Requiere transacciones Spring activas: `SolicitudService.aceptar` mantiene `@Transactional`.
- Verificar `git diff --check`, las pruebas específicas y toda la suite antes del PR.
