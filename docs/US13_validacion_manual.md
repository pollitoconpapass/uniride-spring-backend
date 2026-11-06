# US13 — Pruebas manuales (pasajero)

> Evidencia histórica de esta entrega. Para contratos y ejecución actuales prevalecen
> [endpoints](endpoints.md) y [operación/Flyway](operacion-hardening.md).
> El hardening añade plazos de 12 h, correo con reintentos y validación del esquema;
> los estados pasados de una prueba requieren fixtures, no crear viajes pasados por HTTP.

## Objetivo y alcance
Permitir visualizar todas las solicitudes con sus estados, mostrar un mensaje orientativo al no existir solicitudes y consultar el historial como solo lectura con penalidades PROPIAS del pasajero (si las hubiera).

### Antes de probar
1. Crear rama desde `development` actualizada (PR #42 de US10 ya fusionado).
2. Copiar los archivos del kit y ejecutar sus pruebas y `mvnw test`.
3. Abrir Swagger: `http://localhost:8080/api/swagger-ui.html`; los endpoints se listan normalmente con prefijo `/api`.
4. Obtener un JWT con rol `PASAJERO` y usar Authorize. Para crear datos, usar un segundo usuario `CONDUCTOR`, una ruta y varios viajes, y solicitar/aceptar/rechazar/cancelar solicitudes mediante los endpoints existentes.

### Validaciones
| Caso | Solicitud | Resultado esperado |
|---|---|---|
| 1 | `GET /api/solicitudes` sin solicitudes, con token PASAJERO | 200, `content: []`, cabecera `X-UniRide-Mensaje` indicando ausencia de solicitudes |
| 2 | `GET /api/solicitudes/resumen` sin solicitudes | 200, `solicitudes.content: []`, `mensaje` orientativo en JSON |
| 3 | Crear solicitud y `GET /api/solicitudes` | 200, estado `PENDIENTE` sin cambiar contrato anterior |
| 4 | `GET /api/solicitudes?estado=ACEPTADA` | Solo solicitudes aceptadas; si no hay, mensaje menciona estado |
| 5 | Rechazar o cancelar solicitud y volver a consultar | Estados `RECHAZADA`/`CANCELADA` visibles |
| 6 | `GET /api/solicitudes/historial` con solicitud de viaje pasado | 200, `soloLectura: true`, estado real de la solicitud y `estadoViaje` |
| 7 | Historial sin penalidades propias | `penalidades: []` sin errores |
| 8 | Historial de viaje con penalidad de pasajero | Penalidad propia con `id`, `tipo`, `motivo`, `fecha` (si existe en BD) |
| 9 | Historial donde solo conductor fue penalizado | No mostrar la penalidad de ese conductor al pasajero |
| 10 | `GET /api/solicitudes/historial?page=-1` | 400 por paginacion invalida |
| 11 | Cualquiera de los 3 endpoints sin token/rol adecuado | 401/403 segun configuracion de seguridad |

> `GET /api/solicitudes` conserva `Page<SolicitudRespuesta>` para el frontend existente. El historial y el resumen son endpoints adicionales.

### Comprobaciones de PostgreSQL
```sql
-- Solicitudes del pasajero de prueba (reemplazar :usuario_id con ID real):
SELECT s.id, s.viaje_id, s.estado, s.fecha_creacion
FROM solicitudes s
WHERE s.pasajero_id = 123
ORDER BY s.fecha_creacion DESC;

-- Penalidades de ese pasajero por viaje (NO las penalidades de otros usuarios):
SELECT p.id, p.usuario_id, p.viaje_id, p.tipo, p.motivo, p.fecha
FROM penalidades p
WHERE p.usuario_id = 123
ORDER BY p.fecha DESC;
```
**No se cambia el esquema de la base de datos.** Se consultan las tablas existentes.
