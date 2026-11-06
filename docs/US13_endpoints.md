# US13 — Contratos nuevos (extensión de `docs/endpoints.md`)

> Evidencia histórica de esta entrega. Para contratos y ejecución actuales prevalecen
> [endpoints](endpoints.md) y [operación/Flyway](operacion-hardening.md).
> El hardening añade plazos de 12 h, correo con reintentos y validación del esquema;
> los estados pasados de una prueba requieren fixtures, no crear viajes pasados por HTTP.

Se conservan los contratos anteriores; este documento detalla las variantes nuevas sin reemplazar un `docs/endpoints.md` que ya tiene cambios de US08-US10.

### GET /api/solicitudes
**Existente y sin cambios en el JSON:** `Page<SolicitudRespuesta>`. Query `estado` opcional, `page=0`, `size=10`.
- HTTP 200 cuando la lista está vacía; cabecera `X-UniRide-Mensaje` explica que no tiene solicitudes o que el filtro no coincide.
- HTTP 400 para paginación inválida (`page < 0`, `size < 1` o `size > 100`).
- Requiere rol PASAJERO.

### GET /api/solicitudes/resumen
**Nuevo:** misma query y rol que el endpoint original.

Respuesta 200:
```json
{
  "solicitudes": {"content": [], "totalElements": 0, "totalPages": 0},
  "mensaje": "Aun no tienes solicitudes registradas. Busca un viaje y envia tu primera solicitud."
}
```
Cuando existen solicitudes, `mensaje` es `null`.

### GET /api/solicitudes/historial
**Nuevo:** query `page=0`, `size=10`; rol PASAJERO.

Devuelve las solicitudes propias de viajes con fecha/hora pasadas o con estado `CANCELADO`, `COMPLETADO`, `VENCIDO`, así como solicitudes con estado `RECHAZADA` o `CANCELADA`, ordenadas y paginadas en BD. No modifica datos.

Ejemplo conceptual (sin representar identificadores reales):
```json
{
  "solicitudes": {
    "content": [
      {
        "solicitud": {"id": 55, "estado": "CANCELADA", "viajeId": 42},
        "estadoViaje": "CANCELADO",
        "soloLectura": true,
        "penalidades": [
          {"id": 23, "tipo": "LEVE", "motivo": "Cancelacion tardia", "fecha": "2026-10-30T08:30:00"}
        ]
      }
    ]
  },
  "mensaje": null
}
```
`penalidades` incluye solo las aplicadas al pasajero autenticado y asociadas al viaje de la solicitud. Si no las hay, es una lista vacía `[]`.

Una solicitud aceptada de un viaje futuro programado pertenece al listado normal, no al historial; si su viaje fue cancelado, sí aparece en el historial.
