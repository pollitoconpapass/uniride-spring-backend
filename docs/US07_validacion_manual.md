# US07 — Verificación manual en Swagger (UniRide)

**Rama base:** `development` commit `675ed76053dc77361839b11199099adbb729a440` (PR #37 ya fusionado). **Ruta base de Swagger:** `http://localhost:8080/api/swagger-ui.html` (el gateway puede agregar `/api`).

## Preparación
1. Iniciar PostgreSQL y backend; ingresar como usuario con rol `CONDUCTOR` y perfil personal completo.
2. Publicar una ruta con `POST /rutas` y guardar el `id`. Ejemplo de JSON:

```json
{"origen":"Surco","capacidadMaxima":3,"puntosReferencia":"Puente Primavera","zonasSinParada":"Ninguna","notaContribucion":"Prueba US07"}
```

3. Crear un viaje con `POST /rutas/{rutaId}/viajes`, fecha futura **al menos 3 días después de hoy**. Guardar `viajeId`.

## Escenarios

| Escenario | Acción | HTTP | Persistencia / aviso |
|---|---|---|---|
| Ruta sin pasajeros aceptados | `PUT /rutas/{rutaId}` con capacidad 4, `confirmarImpacto=false` | 200 | Cambia capacidad |
| Campos obligatorios ausentes | `PUT /rutas/{rutaId}` con origen `""`, capacidad `0` o puntos vacíos | 400 | No actualiza |
| Con solicitudes **ACEPTADAS** | `PUT /rutas/{rutaId}` cambiando capacidad, sin confirmar | 409 | No actualiza; mensaje `?confirmarImpacto=true` |
| Con confirmación | Repetir exactamente el mismo PUT con `confirmarImpacto=true` | 200 | Actualiza; notificación a pasajero(s) aceptado(s) |
| Capacidad insuficiente | Una ruta de un viaje con 2 pasajeros aceptados; intentar capacidad 1 incluso con `true` | 409 | No permite sobrecupo |
| Nota de contribución | Cambiar solo `notaContribucion` manteniendo los demás campos | 200 | No requiere confirmación |
| Viaje sin aceptados | `PUT /viajes/{viajeId}` con otra fecha/hora futuras | 200 | Conserva `viajeId` y cambia `fecha`, `hora`, `dia` |
| Viaje con aceptados | `PUT /viajes/{viajeId}` con fecha/hora nuevas y `confirmarImpacto=false` | 409 | No reprograma sin confirmación |
| Reprogramación confirmada | Repetir con `confirmarImpacto=true` | 200 | Avisa a pasajeros aceptados |
| Viaje confirmado | Reprogramar a más de 12 h (con confirmación si hay aceptados) | 200 | `confirmado=false`, `fechaConfirmacion=null`, permite reconfirmar con US17 |
| Viaje `EN_PROGRESO`, `CANCELADO` o `COMPLETADO` | Intentar modificar fecha/hora | 409 | Sin cambios |
| Ruta `ELIMINADA` | Intentar actualizarla | 409 | Sin cambios |

### Solicitud de pasajero para pruebas
1. Cerrar sesión de conductor o reautorizar con cuenta `PASAJERO`.
2. Crear una solicitud para `viajeId` con `POST /solicitudes/viajes/{viajeId}` y un cuerpo válido, siguiendo Swagger. Guardar el ID.
3. Volver a ingresar como conductor y aceptar desde `PUT /solicitudes/{id}/aceptar`.
4. Volver a probar los PUT de arriba; en Swagger verificar los códigos y **notificaciones del pasajero** al confirmar.

### Cuerpo para reprogramar viaje
```json
{"fecha":"2030-12-05","hora":"12:30"}
```
**Sustituir la fecha por un día futuro real** y confirmar que el viaje original era `PROGRAMADO`.

### PostgreSQL
```sql
SELECT id, origen, capacidad_maxima, puntos_referencia, nota_contribucion, estado
FROM rutas WHERE id = <ruta_id>;

SELECT id, ruta_id, fecha, hora, dia, confirmado, fecha_confirmacion,
       recordatorio_enviado, estado
FROM viajes WHERE id = <viaje_id>;

SELECT id, usuario_id, tipo, mensaje, fecha_envio
FROM notificaciones ORDER BY id DESC LIMIT 25;
```
Reemplaza `<ruta_id>` y `<viaje_id>` por números reales (sin los signos angulares).

## No hacer
- No actualizar manualmente columnas de `rutas` o `viajes` para simular cambios.
- No hacer el PR antes de que pasen tests de US07 y `mvnw test` general.
- No modificar `master`.

**Base de datos:** US07 no agrega columnas ni nuevos estados; no requiere migración SQL adicional. Conserva y verifica las migraciones versionadas de US08 y perfiles de roles.
