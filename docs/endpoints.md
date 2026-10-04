# UniRide — Referencia de endpoints

Listado completo de los **42 endpoints** REST de la API, con parámetros, bodies y códigos de estado.
Generado a partir del código fuente (`src/main/java/com/uniride/controllers/`).

---

## Información general

| Concepto | Valor |
|---|---|
| **URL base** | `http://localhost:8080/api` (context-path de la aplicación) |
| **Autenticación** | Header `Authorization: Bearer <token>` (JWT, expira en 24 h) |
| **Público (sin token)** | Solo los 4 endpoints de `/api/auth/**` |
| **Sin token** | Responde `403 Forbidden` (filtro JWT) |
| **Formato de error** | `{"mensaje": "...", "campos": {"campo": "detalle"}}` (`campos` solo en errores de validación) |
| **Paginación** | `page` (base 0, defecto `0`) y `size` (defecto `10`) — los 6 endpoints paginados devuelven `Page<X>` con `content`, `totalPages`, `totalElements`, etc. |

### Códigos de estado

| Código | Cuándo |
|---|---|
| `200` | Éxito (GET, PUT, DELETE con mensaje) |
| `201` | Recurso creado (POST) |
| `204` | Eliminado sin contenido (DELETE de rutas, viajes y cursos) |
| `400` | Validación `@Valid` fallida, parámetro con formato inválido, archivo no permitido, `CamposInvalidosException` |
| `401` | Credenciales o sesión inválidas (`NoAutorizadoException`) |
| `403` | Sin token, o rol insuficiente (`NoPermitidoException`) |
| `404` | Recurso inexistente o ajeno (`ResourceNotFoundException`) |
| `409` | Regla de negocio violada (`BusinessException`) |
| `500` | Error inesperado |

### Enums referenciados

| Enum | Valores |
|---|---|
| `Rol` | `CONDUCTOR`, `PASAJERO` |
| `EstadoRuta` | `ACTIVA`, `LLENA`, `VENCIDA` |
| `EstadoSolicitud` | `PENDIENTE`, `ACEPTADA`, `RECHAZADA`, `CANCELADA` |
| `EstadoViaje` | `PROGRAMADO`, `EN_PROGRESO`, `COMPLETADO`, `CANCELADO`, `VENCIDO` |
| `TipoCompensacion` | `DINERO`, `GASOLINA`, `FAVOR` |
| `TipoNotificacion` | `EXITO`, `ERROR`, `ADVERTENCIA`, `RECORDATORIO` |

> Los enums usados en query params o bodies son **case-sensitive**: un valor fuera de la lista responde `400`.

---

## Módulo 1 — Usuarios, sesión, perfil y horarios

### Autenticación (público)

#### `POST /api/auth/registro`
Crea una cuenta nueva y envía el código de verificación por correo (US01).

**Body (JSON)**
```json
{
  "correo": "juan.perez@upc.edu.pe",
  "contrasena": "MoviUni2026!",
  "nombre": "Juan",
  "apellidos": "Pérez",
  "telefono": "987 654 321",
  "rol": "CONDUCTOR",
  "aceptaTerminos": true
}
```

| Campo | Tipo | Obligatorio | Detalle |
|---|---|---|---|
| `correo` | string | ✅ | Debe ser institucional con `.edu` después del `@` |
| `contrasena` | string | ✅ | Mín. 8 caracteres, una mayúscula, una minúscula y un número o símbolo |
| `nombre` | string | ✅ | |
| `apellidos` | string | ✅ | |
| `telefono` | string | ✅ | Formato `+`, dígitos, espacios o guiones (6–15 caracteres) |
| `rol` | enum `Rol` | ✅ | `CONDUCTOR` o `PASAJERO` |
| `aceptaTerminos` | boolean | ✅ | Debe ser `true` |

**Respuestas:** `201` ✅ `{token, mensaje, usuario}` · `400` campos inválidos · `409` correo o teléfono ya registrado

#### `POST /api/auth/login`
Inicia sesión y devuelve el token JWT (US02).

**Body (JSON)**
```json
{
  "correo": "juan.perez@upc.edu.pe",
  "contrasena": "MoviUni2026!"
}
```

| Campo | Tipo | Obligatorio |
|---|---|---|
| `correo` | string | ✅ |
| `contrasena` | string | ✅ |

**Respuestas:** `200` ✅ `{token, mensaje, usuario}` · `400` campos vacíos · `401` "Correo o contraseña incorrectos" · `409` cuenta no verificada o sin rol principal

#### `POST /api/auth/verificar`
Confirma el código de verificación enviado al correo (US02 / US01).

**Body (JSON)**
```json
{ "correo": "juan.perez@upc.edu.pe", "codigo": "123456" }
```

| Campo | Tipo | Obligatorio |
|---|---|---|
| `correo` | string | ✅ |
| `codigo` | string | ✅ |

**Respuestas:** `200` ✅ cuenta verificada · `400` código incorrecto · `404` no existe ninguna cuenta con ese correo · `409` cuenta ya verificada o código expirado

#### `POST /api/auth/reenviar-codigo`
Reenvía el código de verificación al correo.

**Body (JSON)**
```json
{ "correo": "juan.perez@upc.edu.pe" }
```

**Respuestas:** `200` ✅ código reenviado · `404` no existe ninguna cuenta con ese correo · `409` cuenta ya verificada

### Usuario

#### `GET /api/usuarios/me`
Devuelve el usuario autenticado (US03).

**Parámetros:** ninguno.

**Respuestas:** `200` ✅ `{id, correo, nombre, apellidos, telefono, rol, cuentaVerificada}` · `403` sin token

#### `PUT /api/usuarios/rol`
Cambia el rol principal del usuario (US03, escenario alternativo).

**Body (JSON)**
```json
{ "rol": "PASAJERO" }
```

| Campo | Tipo | Obligatorio |
|---|---|---|
| `rol` | enum `Rol` | ✅ |

**Respuestas:** `200` ✅ usuario actualizado · `400` rol ausente · `403` sin token

### Perfil

#### `GET /api/perfil`
Devuelve el perfil del usuario autenticado (US04).

**Parámetros:** ninguno.

**Respuestas:** `200` ✅ perfil · `404` "Aún no has completado tu perfil" · `403` sin token

#### `GET /api/perfil/usuario/{usuarioId}`
Devuelve el perfil de otro usuario (para evaluar solicitudes recibidas — US14).

| Path | Tipo |
|---|---|
| `usuarioId` | long |

**Respuestas:** `200` ✅ perfil · `404` perfil no encontrado · `403` sin token

#### `PUT /api/perfil`
Guarda los datos personales del perfil (US04).

**Body (JSON)**
```json
{
  "nombre": "Juan",
  "apellidos": "Pérez",
  "carrera": "Ingeniería de Software",
  "distrito": "San Borja",
  "universidad": "UPC",
  "diasDisponibles": "Lunes, Miércoles, Viernes",
  "horariosDisponibles": "07:00 - 09:00",
  "fechaInicioClases": "2026-08-04",
  "fechaFinClases": "2026-12-19",
  "metodoCompensacionFavorito": "GASOLINA"
}
```

| Campo | Tipo | Obligatorio | Detalle |
|---|---|---|---|
| `nombre` | string | ✅ | |
| `apellidos` | string | ✅ | |
| `carrera` | string | ✅ | |
| `distrito` | string | ✅ | |
| `universidad` | string | ✅ | Se usa como **destino** automático de las rutas |
| `diasDisponibles` | string | ✅ | Texto libre |
| `horariosDisponibles` | string | ✅ | Texto libre |
| `fechaInicioClases` | date (`yyyy-MM-dd`) | ✅ | |
| `fechaFinClases` | date (`yyyy-MM-dd`) | ✅ | |
| `metodoCompensacionFavorito` | enum `TipoCompensacion` | Solo conductores | Obligatorio si el rol principal es `CONDUCTOR` |

**Respuestas:** `200` ✅ perfil actualizado · `400` campos faltantes o método favorito ausente en conductor · `403` sin token

#### `PUT /api/perfil/info-adicional`
Guarda gustos, hobbies y datos curiosos (US05).

**Body (JSON)**
```json
{
  "gustos": "Música rock, series de ciencia ficción",
  "hobbies": "Tocar guitarra, videojuegos",
  "datosCuriosos": "Hice un intercambio en Chile"
}
```

| Campo | Tipo | Obligatorio |
|---|---|---|
| `gustos` | string | ❌ |
| `hobbies` | string | ❌ |
| `datosCuriosos` | string | ❌ |

**Respuestas:** `200` ✅ perfil actualizado · `404` "Primero completa tu perfil personal" · `403` sin token

### Cursos y horarios

#### `POST /api/perfil/cursos`
Agrega cursos y horarios manualmente, en lote (US24).

**Body (JSON):** **array** de cursos
```json
[
  { "nombre": "Ingeniería de Software", "dia": "Lunes", "horaInicio": "08:00:00", "horaFin": "10:00:00" },
  { "nombre": "Bases de Datos", "dia": "Miércoles", "horaInicio": "10:00:00", "horaFin": "12:00:00" }
]
```

| Campo | Tipo | Obligatorio | Detalle |
|---|---|---|---|
| `nombre` | string | ✅ | |
| `dia` | string | ✅ | |
| `horaInicio` | time (`HH:mm:ss` o `HH:mm`) | ✅ | |
| `horaFin` | time (`HH:mm:ss` o `HH:mm`) | ✅ | Debe ser posterior a `horaInicio` |

**Respuestas:** `201` ✅ lista de cursos creados · `400` lista vacía o campo de algún curso incompleto/inválido · `403` sin token

#### `GET /api/perfil/cursos`
Lista los cursos del usuario con paginación.

| Query | Tipo | Defecto |
|---|---|---|
| `page` | int | `0` |
| `size` | int | `10` |

**Respuestas:** `200` ✅ `Page<CursoRespuesta>` (ordenado por día) · `403` sin token

#### `DELETE /api/perfil/cursos/{id}`
Elimina un curso (US24).

| Path | Tipo |
|---|---|
| `id` | long |

**Respuestas:** `204` ✅ · `404` curso no encontrado · `403` sin token

#### `POST /api/perfil/horarios/archivo`
Sube masivamente cursos y horarios desde un archivo (US23).

**Body:** `multipart/form-data` con el campo `archivo`

| Regla | Detalle |
|---|---|
| Formatos | Solo `.pdf` |
| Tamaño máx. | 1 MB |

**Respuestas:** `201` ✅ `{mensaje, cursosImportados, nombreArchivo}` · `400` sin archivo, formato no permitido o tamaño mayor a 1 MB · `403` sin token

---

## Módulo 2 — Rutas y viajes publicados

#### `POST /api/rutas`
Publica una ruta como conductor (US06). El **destino** se toma automáticamente de la `universidad` del perfil.

**Body (JSON)**
```json
{
  "origen": "San Borja",
  "capacidadMaxima": 3,
  "puntosReferencia": "Av. Arequipa 1234",
  "zonasSinParada": "Centro de Lima",
  "notaContribucion": "S/ 10 sugeridos por pasajero"
}
```

| Campo | Tipo | Obligatorio | Detalle |
|---|---|---|---|
| `origen` | string | ✅ | Punto de salida |
| `capacidadMaxima` | int | ✅ | Mínimo `1` |
| `puntosReferencia` | string | ✅ | |
| `zonasSinParada` | string | ❌ | |
| `notaContribucion` | string | ❌ | Referencia no obligatoria (US06, alternativo) |

**Respuestas:** `201` ✅ ruta creada (estado `ACTIVA`) · `400` campos faltantes o perfil sin universidad · `403` rol distinto de `CONDUCTOR` · `404` perfil inexistente

#### `GET /api/rutas`
Lista **mis rutas** publicadas con filtros (US09).

| Query | Tipo | Defecto | Detalle |
|---|---|---|---|
| `estado` | enum `EstadoRuta` | – | Filtro opcional (`ACTIVA`, `LLENA`, `VENCIDA`) |
| `page` | int | `0` | |
| `size` | int | `10` | |

**Respuestas:** `200` ✅ `Page<RutaRespuesta>` (vacío si no hay rutas) · `400` enum inválido · `403` sin token

#### `PUT /api/rutas/{id}`
Edita una ruta propia (US07).

| Path | Tipo |
|---|---|
| `id` | long |

**Body (JSON):** mismo esquema que `POST /api/rutas`.

**Respuestas:** `200` ✅ ruta actualizada · `400` campos inválidos · `404` ruta inexistente o ajena · `403` sin token

#### `DELETE /api/rutas/{id}`
Elimina una ruta propia (US08).

| Path | Tipo |
|---|---|
| `id` | long |

**Respuestas:** `204` ✅ · `404` ruta inexistente o ajena · `403` sin token

#### `POST /api/rutas/{rutaId}/viajes`
Genera un viaje programado a partir de una ruta propia (el día se calcula de la fecha).

**Body (JSON)**
```json
{
  "fecha": "2026-10-12",
  "hora": "07:30:00"
}
```

| Campo | Tipo | Obligatorio |
|---|---|---|
| `fecha` | date (`yyyy-MM-dd`) | ✅ — no puede ser en el pasado |
| `hora` | time (`HH:mm:ss` o `HH:mm`) | ✅ |

**Respuestas:** `201` ✅ viaje creado en estado `PROGRAMADO` · `400` campos faltantes o fecha en el pasado · `404` ruta inexistente o ajena · `403` sin token

#### `GET /api/rutas/{rutaId}/viajes`
Lista los viajes de una ruta propia.

| Path | Tipo |
|---|---|
| `rutaId` | long |

| Query | Tipo | Defecto |
|---|---|---|
| `page` | int | `0` |
| `size` | int | `10` |

**Respuestas:** `200` ✅ `Page<ViajeRespuesta>` · `404` ruta inexistente o ajena · `403` sin token

---

## Módulo 3 — Búsqueda y solicitudes (pasajero)

#### `GET /api/viajes/buscar`
Busca viajes disponibles filtrando por origen, destino, día, hora y cercanía (US10). Solo viajes en estado `PROGRAMADO`.

| Query | Tipo | Defecto | Detalle |
|---|---|---|---|
| `origen` | string | – | Opcional, sin distinción de mayúsculas |
| `destino` | string | – | Opcional |
| `dia` | string | – | Opcional (ej. `Lunes`) |
| `hora` | time (`HH:mm` o `HH:mm:ss`) | – | Opcional |
| `cercania` | boolean | `false` | `true` = por distrito del perfil (requiere perfil completo) |
| `page` | int | `0` | |
| `size` | int | `10` | |

**Respuestas:** `200` ✅ `Page<BusquedaRespuesta>` (con `notaContribucion` de la ruta) · `400` `hora` con formato inválido · `404` perfil incompleto al usar `cercania=true` · `403` sin token

#### `POST /api/solicitudes/viajes/{viajeId}`
Envía una solicitud para unirse a un viaje, con preferencia de compensación y método opcional (US11).

| Path | Tipo |
|---|---|
| `viajeId` | long |

**Body (JSON)**
```json
{
  "mensaje": "¡Hola! Me interesa unirme a tu viaje.",
  "preferenciaCompensacion": "DINERO",
  "metodoCompensacionId": 1
}
```

| Campo | Tipo | Obligatorio | Detalle |
|---|---|---|---|
| `mensaje` | string | ❌ | |
| `preferenciaCompensacion` | enum `TipoCompensacion` | ✅ | |
| `metodoCompensacionId` | long | ❌ | Método propio; si se envía, su `tipo` debe coincidir con `preferenciaCompensacion` |

**Respuestas**
- `201` ✅ solicitud en estado `PENDIENTE` (incluye `advertencia` si la preferencia difiere de la del conductor)
- `400` campos inválidos
- `404` viaje no encontrado, o método de compensación inexistente/ajeno
- `409` viaje propio · viaje ya ocurrido · viaje no `PROGRAMADO` · solicitud duplicada · sin cupos · método desactivado · método que no coincide con la preferencia

#### `GET /api/solicitudes`
Lista mis solicitudes enviadas con su estado (US13).

| Query | Tipo | Defecto | Detalle |
|---|---|---|---|
| `estado` | enum `EstadoSolicitud` | – | Filtro opcional |
| `page` | int | `0` | |
| `size` | int | `10` | |

**Respuestas:** `200` ✅ `Page<SolicitudRespuesta>` · `400` enum inválido · `403` sin token

#### `PUT /api/solicitudes/{id}/cancelar`
Cancela una solicitud propia pendiente (US12).

| Path | Tipo |
|---|---|
| `id` | long |

**Respuestas:** `200` ✅ solicitud `CANCELADA` + notificación al conductor · `404` solicitud inexistente o ajena · `409` solicitud no pendiente · viaje no `PROGRAMADO` · viaje ya ocurrió

#### `PUT /api/solicitudes/{id}/aceptar`
Acepta una solicitud (conductor) y genera el borrador de acuerdo de compensación (US15).

| Path | Tipo |
|---|---|
| `id` | long |

**Respuestas:** `200` ✅ solicitud `ACEPTADA` con `acuerdoTerminos` y `acuerdoEstado=BORRADOR`; consume un cupo · `404` solicitud inexistente o ajena · `409` solicitud ya procesada · viaje no activo · ruta sin cupos

#### `PUT /api/solicitudes/{id}/rechazar`
Rechaza una solicitud con motivo opcional (US16).

| Path | Tipo |
|---|---|
| `id` | long |

**Body (JSON)** — **opcional**
```json
{ "motivo": "No puedo hacer el paro ese día" }
```

| Campo | Tipo | Obligatorio |
|---|---|---|
| `motivo` | string | ❌ |

**Respuestas:** `200` ✅ solicitud `RECHAZADA` (notifica al pasajero) · `404` solicitud inexistente o ajena · `409` solicitud ya procesada o viaje no activo

#### `PUT /api/solicitudes/rechazar-multiples`
Rechaza varias solicitudes en lote con el mismo motivo (US16, alternativo).

**Body (JSON)**
```json
{
  "ids": [15, 16, 17],
  "motivo": "La ruta ya está completa"
}
```

| Campo | Tipo | Obligatorio |
|---|---|---|
| `ids` | array de long | ✅ — al menos uno |
| `motivo` | string | ❌ |

**Respuestas:** `200` ✅ lista de solicitudes rechazadas · `400` `ids` vacío · `404` alguna solicitud inexistente o ajena · `409` alguna solicitud ya procesada

---

## Módulo 4 — Coordinación del viaje (conductor)

#### `GET /api/viajes/{viajeId}/solicitudes`
Lista las solicitudes recibidas de un viaje propio, con el perfil del pasajero (US14).

| Path | Tipo |
|---|---|
| `viajeId` | long |

| Query | Tipo | Defecto | Detalle |
|---|---|---|---|
| `estado` | enum `EstadoSolicitud` | – | Filtro opcional |
| `page` | int | `0` | |
| `size` | int | `10` | |

**Respuestas:** `200` ✅ `Page<SolicitudRespuesta>` (incluye `pasajeroCarrera`, `pasajeroDistrito`, `metodoCompensacion*`) · `404` viaje inexistente o ajeno · `400` enum inválido · `403` sin token

#### `PUT /api/viajes/{id}/confirmar`
Confirma un viaje al menos 24 horas antes de la salida (US17).

| Path | Tipo |
|---|---|
| `id` | long |

**Respuestas**
- `200` ✅ viaje confirmado (notifica a los pasajeros aceptados); si ya estaba confirmado responde `200` con `mensaje: "El viaje ya estaba confirmado"`
- `409` si el plazo de 24 h ya venció: el viaje pasa a `VENCIDO` y se registra una `Penalidad LEVE` (esta es la única respuesta `409` con cuerpo `ViajeRespuesta`)
- `409` viaje no `PROGRAMADO` o ya ocurrió · `404` viaje inexistente o ajeno

#### `PUT /api/viajes/{id}/iniciar`
Pasa el viaje a `EN_PROGRESO` (ciclo de vida: `PROGRAMADO → EN_PROGRESO`).

| Path | Tipo |
|---|---|
| `id` | long |

**Respuestas:** `200` ✅ estado `EN_PROGRESO` (notifica a conductor y pasajeros aceptados); si ya estaba iniciado, `200` con `mensaje` · `409` viaje no `PROGRAMADO` o no confirmado · `404` viaje inexistente o ajeno

#### `PUT /api/viajes/{id}/completar`
Pasa el viaje a `COMPLETADO` (`EN_PROGRESO → COMPLETADO`).

| Path | Tipo |
|---|---|
| `id` | long |

**Respuestas:** `200` ✅ estado `COMPLETADO` (notifica a todos); si ya estaba completado, `200` con `mensaje` · `409` viaje no está en `EN_PROGRESO` · `404` viaje inexistente o ajeno

#### `PUT /api/viajes/{id}/cancelar`
Cancela un viaje, con penalidad según la anticipación (US18).

| Path | Tipo |
|---|---|
| `id` | long |

**Respuestas**
- `200` ✅ viaje `CANCELADO` con notificación a todos los involucrados; si ya estaba cancelado, `200` con `mensaje: "El viaje ya estaba cancelado"`
- `403` si no es el conductor ni un pasajero con solicitud aceptada
- `409` viaje no `PROGRAMADO` o ya ocurrió · `404` viaje inexistente o ajeno

> Penalidad aplicada por el servicio según anticipación: **> 24 h** sin penalidad · **4–24 h** `LEVE` · **< 4 h** `GRAVE`.

#### `DELETE /api/viajes/{id}`
Elimina un viaje propio.

| Path | Tipo |
|---|---|
| `id` | long |

**Respuestas:** `204` ✅ (notifica al conductor) · `404` viaje inexistente o ajeno · `403` sin token

---

## Módulo 5 — Historial y estadísticas

#### `GET /api/estadisticas`
Totales de actividad del usuario autenticado (US19).

**Parámetros:** ninguno.

**Respuestas:** `200` ✅ `{viajesComoConductor, viajesComoPasajero, viajesCancelados, penalidadesAcumuladas}` · `403` sin token

#### `GET /api/estadisticas/semana`
Frecuencia de viajes por día de la semana (US20).

**Parámetros:** ninguno.

**Respuestas:** `200` ✅ `{suficientesDatos, mensaje, viajesPorDia[{dia, cantidad}], canceladosPorMi, canceladosPorTerceros}` — con menos de 5 viajes completados, `suficientesDatos: false` y el `mensaje` de "necesitas más datos" · `403` sin token

#### `GET /api/estadisticas/rutas`
Ranking de combinaciones origen–destino más usadas, con penalidades por ruta (US21, solo conductores).

**Parámetros:** ninguno.

**Respuestas:** `200` ✅ `{suficientesDatos, mensaje, ranking[{origen, destino, cantidad, penalidades}]}` · `403` sin token

#### `GET /api/historial/pdf`
Exporta el historial personal de viajes en PDF (US22).

| Query | Tipo | Defecto | Detalle |
|---|---|---|---|
| `desde` | date (`yyyy-MM-dd`) | – | Filtro opcional |
| `hasta` | date (`yyyy-MM-dd`) | – | Filtro opcional |
| `tipo` | string | `todos` | `todos` \| `conductor` \| `pasajero` (sin distinción de mayúsculas) |

**Respuestas:** `200` ✅ archivo PDF (`application/pdf`, `Content-Disposition: attachment`) · `400` `desde` posterior a `hasta` · `409` sin historial que exportar o `tipo` inválido · `403` sin token

---

## Módulo 6 — Notificaciones

**Sin endpoints REST.** Las notificaciones se generan internamente cuando el sistema procesa acciones (éxito/error/recordatorios) y se guardan en la tabla `notificaciones` junto con el envío de correo. No existe un controlador para consultarlas por API.

---

## Módulo 7 — Pagos y compensación

#### `GET /api/metodos-compensacion`
Lista mis métodos de compensación registrados (US26).

**Parámetros:** ninguno.

**Respuestas:** `200` ✅ lista `[{id, tipo, descripcion, activo}]` (no paginada) · `403` sin token

#### `POST /api/metodos-compensacion`
Registra un método de compensación (US26).

**Body (JSON)**
```json
{
  "tipo": "DINERO",
  "descripcion": "Yape - Juan Pérez",
  "activo": true
}
```

| Campo | Tipo | Obligatorio | Detalle |
|---|---|---|---|
| `tipo` | enum `TipoCompensacion` | ✅ | |
| `descripcion` | string | ✅ | Ej. "Yape - Juan", "Visa ****1234" |
| `activo` | boolean | ❌ | En el registro se fuerza `true` |

**Respuestas:** `201` ✅ método creado (notificación de éxito) · `400` campos faltantes · `409` ya existe un método con el mismo `tipo` y `descripcion` (ignorando mayúsculas) · `403` sin token

#### `PUT /api/metodos-compensacion/{id}`
Actualiza un método propio (US27).

| Path | Tipo |
|---|---|
| `id` | long |

**Body (JSON):** mismo esquema que el registro (`tipo` y `descripcion` obligatorios).

**Respuestas:** `200` ✅ método actualizado · `400` campos incompletos (no modifica los datos anteriores) · `404` método inexistente o ajeno · `409` duplicado con otro método propio

#### `DELETE /api/metodos-compensacion/{id}`
Elimina un método propio (US28).

| Path | Tipo |
|---|---|
| `id` | long |

**Respuestas**
- `200` ✅ `{"mensaje": "Método de compensación eliminado correctamente"}` (nota: responde `200` con cuerpo, no `204`)
- `404` método inexistente o ajeno
- `409` "No puedes eliminar este método: está asociado a un viaje confirmado" (cuando una solicitud `ACEPTADA` con ese método pertenece a un viaje confirmado)

#### `GET /api/viajes/{id}/sugerencia-contribucion`
Sugerencia de contribución estimada para un viaje (US29).

| Path | Tipo |
|---|---|
| `id` | long — viaje en estado `PROGRAMADO` |

| Query | Tipo | Defecto | Detalle |
|---|---|---|---|
| `pasajeros` | int | – | **Obligatorio en la práctica** (mínimo 1) |

**Fórmula:** `total = S/8.00 × 1.5` si es hora pico (06:00–10:00 o 17:00–21:00), si no `× 1.0`; `aporte = total / pasajeros`.

**Respuestas**
- `200` ✅ `{viajeId, origen, destino, dia, fecha, hora, pasajeros, esHoraPico, costoTotalEstimado, aporteEstimado, litrosGasolinaAprox, sugerenciaFavor, nota}`
- `400` `pasajeros` ausente ("Debes indicar la cantidad de pasajeros para calcular la sugerencia") o menor a 1
- `404` viaje no encontrado
- `409` viaje no `PROGRAMADO`
- `403` sin token

---

## Índice rápido

| # | Método | Ruta | Módulo |
|---|---|---|---|
| 1 | POST | `/api/auth/registro` | 1 |
| 2 | POST | `/api/auth/login` | 1 |
| 3 | POST | `/api/auth/verificar` | 1 |
| 4 | POST | `/api/auth/reenviar-codigo` | 1 |
| 5 | GET | `/api/usuarios/me` | 1 |
| 6 | PUT | `/api/usuarios/rol` | 1 |
| 7 | GET | `/api/perfil` | 1 |
| 8 | GET | `/api/perfil/usuario/{usuarioId}` | 1 |
| 9 | PUT | `/api/perfil` | 1 |
| 10 | PUT | `/api/perfil/info-adicional` | 1 |
| 11 | POST | `/api/perfil/cursos` | 1 |
| 12 | GET | `/api/perfil/cursos` | 1 |
| 13 | DELETE | `/api/perfil/cursos/{id}` | 1 |
| 14 | POST | `/api/perfil/horarios/archivo` | 1 |
| 15 | POST | `/api/rutas` | 2 |
| 16 | GET | `/api/rutas` | 2 |
| 17 | PUT | `/api/rutas/{id}` | 2 |
| 18 | DELETE | `/api/rutas/{id}` | 2 |
| 19 | POST | `/api/rutas/{rutaId}/viajes` | 2 |
| 20 | GET | `/api/rutas/{rutaId}/viajes` | 2 |
| 21 | GET | `/api/viajes/buscar` | 3 |
| 22 | POST | `/api/solicitudes/viajes/{viajeId}` | 3 |
| 23 | GET | `/api/solicitudes` | 3 |
| 24 | PUT | `/api/solicitudes/{id}/cancelar` | 3 |
| 25 | PUT | `/api/solicitudes/{id}/aceptar` | 3 |
| 26 | PUT | `/api/solicitudes/{id}/rechazar` | 3 |
| 27 | PUT | `/api/solicitudes/rechazar-multiples` | 3 |
| 28 | GET | `/api/viajes/{viajeId}/solicitudes` | 4 |
| 29 | PUT | `/api/viajes/{id}/confirmar` | 4 |
| 30 | PUT | `/api/viajes/{id}/iniciar` | 4 |
| 31 | PUT | `/api/viajes/{id}/completar` | 4 |
| 32 | PUT | `/api/viajes/{id}/cancelar` | 4 |
| 33 | DELETE | `/api/viajes/{id}` | 4 |
| 34 | GET | `/api/estadisticas` | 5 |
| 35 | GET | `/api/estadisticas/semana` | 5 |
| 36 | GET | `/api/estadisticas/rutas` | 5 |
| 37 | GET | `/api/historial/pdf` | 5 |
| 38 | GET | `/api/metodos-compensacion` | 7 |
| 39 | POST | `/api/metodos-compensacion` | 7 |
| 40 | PUT | `/api/metodos-compensacion/{id}` | 7 |
| 41 | DELETE | `/api/metodos-compensacion/{id}` | 7 |
| 42 | GET | `/api/viajes/{id}/sugerencia-contribucion` | 7 |
