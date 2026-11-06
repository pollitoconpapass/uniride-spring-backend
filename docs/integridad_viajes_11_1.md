# Corrección transversal 11.1 — Integridad de eliminación de viajes

> Evidencia histórica de esta entrega. Para contratos y ejecución actuales prevalecen
> [endpoints](endpoints.md) y [operación/Flyway](operacion-hardening.md).
> El hardening añade plazos de 12 h, correo con reintentos y validación del esquema;
> los estados pasados de una prueba requieren fixtures, no crear viajes pasados por HTTP.

Base verificada: `development` commit `19fdae45b3a8cff151d88e860f0ba1a4aad07c93` (PR #45 US25).

## Problema reproducible

`DELETE /api/viajes/{id}` realizaba `viajeRepository.delete(viaje)` sin verificar solicitudes, acuerdos de compensación y penalidades. En PostgreSQL, las FK pueden provocar `500` cuando hay dependencias. Además, se puede perder el viaje al que se refieren estadísticas e historial.

## Nueva regla propuesta

- Viaje `PROGRAMADO`, sin confirmar, sin solicitudes de **ningún estado** y sin penalidades: `204 No Content`; se elimina y notifica al conductor.
- Viaje con cualquier solicitud (PENDIENTE, ACEPTADA, RECHAZADA o CANCELADA): `409 Conflict`; se conserva todo; se recomienda `PUT /api/viajes/{id}/cancelar` cuando corresponda.
- Viaje con penalidades: `409 Conflict`, se protege historial.
- Viaje confirmado o estado `EN_PROGRESO`, `COMPLETADO`, `VENCIDO` o `CANCELADO`: `409 Conflict`.
- ID inexistente o de otro conductor: `404 Not Found`.

La regla de cancelación **US18** no se cambia: de acuerdo al informe puede cancelar un conductor o un pasajero aceptado, sujeto a las penalidades establecidas.

## Pruebas

- `IntegridadEliminacionViajesServiceTest`: 10 pruebas.
- `IntegridadEliminacionViajesRepositoryTest`: 2 pruebas de persistencia H2.
- Ejecutar `./mvnw.cmd "-Dtest=IntegridadEliminacionViajesServiceTest,IntegridadEliminacionViajesRepositoryTest" test` desde PowerShell.
- Ejecutar `./mvnw.cmd test` para regresión.

## Validación manual Swagger con JWT CONDUCTOR

1. Crear una ruta y un viaje futuro sin solicitudes. `DELETE /api/viajes/{id}` debe dar 204, y GET/listado ya no mostrarlo.
2. Crear otro viaje, registrar como pasajero una solicitud PENDIENTE y probar DELETE como conductor; esperar 409 con mensaje orientativo. Verificar SELECT en `viajes` y `solicitudes`: ambas filas deben persistir.
3. Rechazar la solicitud y repetir DELETE: continuar esperando 409, porque el historial también se conserva.
4. Como conductor, cancelar ese viaje mediante PUT y revisar los estados/avisos según US18.
5. Con un viaje confirmado (aunque no tenga pasajeros), DELETE debe devolver 409.
6. Como un conductor diferente, DELETE sobre el ID ajeno debe devolver 404.
7. Ejecutar pruebas de US17 y US18 existentes; la corrección no debe alterar confirmar, iniciar, completar ni cancelar.

SQL de observación (sustituye los IDs de prueba):

```sql
SELECT id, ruta_id, estado, confirmado FROM viajes WHERE id = 123;
SELECT id, viaje_id, estado FROM solicitudes WHERE viaje_id = 123;
SELECT id, viaje_id, tipo FROM penalidades WHERE viaje_id = 123;
```

## Limitaciones

Este cambio no implementa bloqueo de concurrencia para aceptar solicitudes (paso 11.2); no afirmamos que ya esté solucionado. La protección ante eliminación simultánea con inserción de nuevas dependencias también requiere una transacción/serialización coordinada: `flush()` detecta el fallo antes del aviso, pero podría seguir devolviendo 500 ante una carrera real. Añadir pruebas concurrentes y bloqueo en la siguiente corrección.

No requiere migraciones SQL; utiliza el estado y las relaciones existentes.
