# US14 — Ver solicitudes recibidas por viaje (agrupadas por ruta)

**Base:** `/api`, si el proyecto usa el context-path configurado actualmente.
**Seguridad:** JWT con rol activo `CONDUCTOR`. Se limita a las rutas del conductor autenticado.

## `GET /api/solicitudes/recibidas/por-ruta`

Agrupa las solicitudes recibidas según la ruta publicada. Incluye las rutas sin solicitudes, para mostrar una recomendación. La **paginación es por ruta**, no por solicitud.

| Query | Tipo | Por defecto | Regla |
| --- | --- | --- | --- |
| `estado` | `PENDIENTE`, `ACEPTADA`, `RECHAZADA`, `CANCELADA` | Sin filtro | Opcional |
| `page` | entero | `0` | >= 0 |
| `size` | entero | `10` | 1 a 100 |

**Respuestas:** `200` con `rutas` (Page) y `mensaje` opcional; `400` parámetros inválidos; `401` sin autenticación, `403` si no tiene rol de conductor.

Cada elemento de `rutas.content` tiene `rutaId`, `origen`, `destino`, `estado`, `solicitudes` y `mensaje`. Las solicitudes muestran `solicitudId`, `viajeId`, `fechaViaje`, `horaViaje`, `estado`, `pasajeroNombre`, `pasajeroCarrera`, `pasajeroDistrito`, `mensajeSolicitud`, `fechaSolicitud`.

## `GET /api/solicitudes/recibidas/{solicitudId}/detalle`

Devuelve el perfil del pasajero y su mensaje de solicitud. Solo el conductor **propietario de la ruta asociada** puede consultarlo. Una solicitud ajena devuelve `404` sin revelar la identidad de su propietario.

**Respuestas:** `200` con `solicitudId`, `rutaId`, `viajeId`, `estado`, `mensajeSolicitud`, `pasajero` (nombre, apellidos, carrera, distrito, universidad, gustos y hobbies) y `mensaje` opcional; `404` si no existe o no pertenece al conductor.

No se devuelven contraseñas, teléfono, horarios personales ni correo institucional en el detalle del perfil.

## Compatibilidad

No se modifica el comportamiento de `GET /api/viajes/{viajeId}/solicitudes`, `GET /api/solicitudes`, ni de sus acciones de aceptación y rechazo. Tampoco hay cambios de base de datos.

**Limitación consciente:** se paginan las rutas antes de recuperar sus solicitudes. Para rutas con cantidades muy grandes de interesados, convendría incorporar una paginación secundaria por solicitud en una futura optimización.
