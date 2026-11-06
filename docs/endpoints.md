# UniRide — Referencia de endpoints

Inventario completo de los **49 mappings HTTP** REST de la API, con parámetros, bodies y códigos de estado.
Generado a partir del código fuente (`src/main/java/com/uniride/controllers/`).

---

## Información general

| Concepto | Valor |
|---|---|
| **URL base** | `http://localhost:8080/api` (context-path de la aplicación) |
| **Autenticación** | Header `Authorization: Bearer <token>` (JWT, expira en 24 h) |
| **Público (sin token)** | Los 4 endpoints de autenticación y Swagger/OpenAPI (`/api/v3/api-docs/**`, `/api/swagger-ui/**`, `/api/swagger-ui.html`) |
| **Sin token** | Responde `403 Forbidden` (filtro JWT) |
| **Formato de error** | `{"mensaje": "...", "campos": {"campo": "detalle"}}` (`campos` solo en errores de validación) |
| **Paginación** | `page >= 0` (defecto `0`) y `size` entre `1..100` (defecto `10`). Hay 11 mappings paginados: 6 devuelven `Page<X>` y 5 un resumen que contiene la página; ver índice y documentación US13/US14. Parámetros inválidos: `400`. |

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
| `429` | Límite de autenticación, bloqueo OTP o enfriamiento de reenvío |
| `405` / `415` | Método HTTP / tipo de contenido no admitido |
| `500` | Error inesperado |

### Enums referenciados

| Enum | Valores |
|---|---|
| `Rol` | `CONDUCTOR`, `PASAJERO` |
| `EstadoRuta` | `ACTIVA`, `LLENA`, `VENCIDA`, `ELIMINADA` |
| `EstadoSolicitud` | `PENDIENTE`, `ACEPTADA`, `RECHAZADA`, `CANCELADA` |
| `EstadoViaje` | `PROGRAMADO`, `EN_PROGRESO`, `COMPLETADO`, `CANCELADO`, `VENCIDO` |
| `TipoCompensacion` | `DINERO`, `GASOLINA`, `FAVOR` |
| `TipoNotificacion` | `EXITO`, `ERROR`, `ADVERTENCIA`, `RECORDATORIO` |

> Los enums usados en query params o bodies son **case-sensitive**: un valor fuera de la lista responde `400`.

---

## Módulo 1 — Usuarios, sesión, perfil y horarios

### Autorización por rol

UniRide utiliza una única cuenta por usuario con un rol activo que puede
alternarse entre `CONDUCTOR` y `PASAJERO`.

El cambio de rol no crea una segunda cuenta ni elimina rutas, solicitudes
o historial anteriores.

El rol activo puede modificarse mediante:

`PUT /api/usuarios/rol`

#### CONDUCTOR

Puede gestionar rutas y viajes propios, revisar solicitudes recibidas,
confirmar, iniciar y completar viajes.

#### PASAJERO

Puede buscar viajes disponibles, crear y gestionar solicitudes y consultar
sugerencias de contribución.

#### Funcionalidades compartidas

Perfil, horarios académicos, métodos de compensación, historial,
estadísticas generales y cancelación de viajes según participación.

Los endpoints restringidos responden `403 Forbidden` cuando el usuario
autenticado no posee el rol requerido.

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
| `correo` | string | ✅ | Dominio institucional terminado en `.edu` o `.edu.xx`, formato válido y máximo 254 caracteres; se recorta y convierte a minúsculas |
| `contrasena` | string | ✅ | Mín. 8 caracteres, una mayúscula, una minúscula, un número y un símbolo; máximo 72 bytes UTF-8 |
| `nombre` | string | ✅ | |
| `apellidos` | string | ✅ | |
| `telefono` | string | ✅ | Número válido según libphonenumber, PE por defecto; admite espacios, paréntesis y guiones, se normaliza a E.164 y detecta duplicados equivalentes |
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

Cambia el rol activo de la cuenta entre `CONDUCTOR` y `PASAJERO`
sin crear una cuenta adicional.

**Body**

```json
{
  "rol": "PASAJERO"
}
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
Guarda y edita gustos, hobbies y datos curiosos (US05).

**Primera carga:** se requieren los tres campos con texto no vacío. El servicio devuelve
`400` indicando los nombres de los campos que faltan. El perfil personal debe existir previamente.

**Body inicial (JSON)**
```json
{
  "gustos": "Música rock, series de ciencia ficción",
  "hobbies": "Tocar guitarra, videojuegos",
  "datosCuriosos": "Hice un intercambio en Chile"
}
```

**Edición parcial:** en una actualización posterior se pueden omitir campos (o enviarlos como `null`)
para conservar sus valores actuales; se debe enviar al menos un campo no nulo. Enviar una
cadena vacía o solo espacios en un campo obligatorio provoca `400`, sin borrar su valor anterior.

```json
{
  "hobbies": "Ciclismo y fotografía"
}
```

| Campo | Tipo | Al completar inicialmente | En edición posterior |
|---|---|---|---|
| `gustos` | string | Obligatorio, no vacío | Opcional; si se omite, se conserva |
| `hobbies` | string | Obligatorio, no vacío | Opcional; si se omite, se conserva |
| `datosCuriosos` | string | Obligatorio, no vacío | Opcional; si se omite, se conserva |

**Respuestas:** `200` perfil actualizado y notificación de éxito · `400` campos
obligatorios faltantes, valores en blanco o solicitud sin cambios · `404` perfil personal aún no registrado ·
`401` sin autenticación válida (según configuración de seguridad).

**Nota:** no se modifican las tablas `perfiles`, `usuarios`, `conductores` ni `pasajeros`.

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

**Respuestas:** `201` ✅ lista de cursos creados y notificación de éxito · `400` lista vacía, curso nulo, campo obligatorio vacío o JSON/hora con formato incorrecto · `409` cruce de horarios · `401` sin autenticación válida.

**US24 — ejemplos de error HTTP 400:**

Si falta el nombre, por ejemplo:

```json
[
  { "nombre": "", "dia": "Lunes", "horaInicio": "08:00", "horaFin": "10:00" }
]
```

Respuesta de error (se mantiene el contrato `ErrorRespuesta`):

```json
{ "mensaje": "Falta ingresar el nombre de un curso", "campos": null }
```

Si `dia`, `horaInicio` o `horaFin` son nulos/faltantes, se devuelve HTTP `400` con el mensaje específico del campo. Si una hora tiene un formato no interpretable (por ejemplo `25:99`) o el JSON es inválido, se devuelve HTTP `400` con una indicación para revisar el formato; no HTTP `500`.

Cuando los cursos son válidos, la operación devuelve `201` con la lista guardada y registra la notificación de éxito `Cursos y horarios guardados`. En caso de cruce de horarios, se devuelve HTTP `409` y no se guardan los cursos del lote.

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
Sube masivamente cursos y horarios desde un archivo PDF o TXT (US23).

**Body:** `multipart/form-data` con el campo `archivo`

| Regla | Detalle |
|---|---|
| Formatos | `.pdf` o `.txt` |
| Tamaño máx. | 1 MB |

**Respuestas:** `201` ✅ `{mensaje, cursosImportados, cursosOmitidos, nombreArchivo}` · `400` sin archivo, formato no permitido o tamaño mayor a 1 MB · `403` sin token

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
| `estado` | enum `EstadoRuta` | – | Filtro opcional (`ACTIVA`, `LLENA`, `VENCIDA`, `ELIMINADA`) |
| `page` | int | `0` | |
| `size` | int | `10` | |

**US09:** cada consulta sincroniza el estado de las rutas del conductor a partir de sus viajes:
`ACTIVA` si no tiene viajes o existe al menos un viaje PROGRAMADO futuro con cupo;
`LLENA` si todos los viajes PROGRAMADO futuros están completos;
`VENCIDA` cuando tiene viajes pero ninguno PROGRAMADO futuro;
`ELIMINADA` se conserva sin alterar (US08). El estado actualizado se guarda en `rutas.estado`.

**Respuestas:** `200` ✅ `Page<RutaRespuesta>`; si no hay rutas, cabecera HTTP
`X-UniRide-Mensaje` con una orientación. · `400` enum inválido · `403` sin token.

#### `GET /api/rutas/resumen`
Nueva variante no disruptiva para US09 que devuelve paginación, filtro y mensaje en el JSON:

```json
{
  "rutas": { "content": [], "totalElements": 0, "totalPages": 0 },
  "mensaje": "Aun no tienes rutas visibles. Publica una ruta o revisa tus rutas eliminadas."
}
```

Acepta los mismos parámetros `estado`, `page` y `size` que `GET /api/rutas`.
Cuando hay resultados, `mensaje` es `null`. Conserva el endpoint anterior para
no romper el frontend existente.

#### `PUT /api/rutas/{id}`
Edita una ruta propia (US07).

| Path | Tipo |
|---|---|
| `id` | long |

**Body (JSON):** mismo esquema que `POST /api/rutas`.

| Query | Tipo | Por defecto |
|---|---|---|
| `confirmarImpacto` | boolean | `false` |

Si hay pasajeros aceptados y cambian capacidad, origen o puntos de referencia,
la API responde **409** pidiendo confirmación con `?confirmarImpacto=true`.
Aunque se confirme, nunca permite bajar la capacidad por debajo de las solicitudes
aceptadas de un viaje PROGRAMADO. Se notificará a los pasajeros afectados.
Las notas de contribución pueden cambiarse sin confirmación.
El horario no está en la ruta, sino en los viajes: usa `PUT /api/viajes/{id}`.

**Respuestas:** `200` ✅ ruta actualizada · `400` campos inválidos · `409` confirmación necesaria, capacidad insuficiente o viaje en progreso · `404` ruta inexistente o ajena · `403` sin token

#### `DELETE /api/rutas/{id}`
Elimina una ruta propia (US08).

| Path | Tipo |
|---|---|
| `id` | long |

**Respuestas:** `204` ✅ · `404` ruta inexistente o ajena · `403` sin token

La eliminación es lógica. `?confirmar=true` reconoce el impacto si hay pasajeros
aceptados o salidas a menos de 4 h; sin confirmación responde 409. Un viaje en
progreso impide eliminar la ruta incluso con confirmación. Los futuros PROGRAMADO
se cancelan con las reglas US18 y se conserva el historial. Repetir la eliminación
no vuelve a cancelar viajes; conserva el aviso existente «Ruta ya eliminada».

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

La fecha y hora completas deben ser futuras y permitir confirmar con al menos 12 horas de anticipación.

**Respuestas:** `201` ✅ viaje creado en estado `PROGRAMADO` · `400` campos faltantes o salida pasada · `409` anticipación menor a 12 horas o ruta eliminada · `404` ruta inexistente o ajena · `403` sin token o rol insuficiente

#### `PUT /api/viajes/{id}` (US07)
Reprograma un viaje PROGRAMADO de una ruta propia. Requiere rol `CONDUCTOR`.

**Body:** el mismo que en `POST /api/rutas/{rutaId}/viajes`, con `fecha` y `hora`.

| Query | Tipo | Por defecto |
|---|---|---|
| `confirmarImpacto` | boolean | `false` |

Si hay pasajeros aceptados, devuelve **409** con advertencia hasta que se reintente
con `?confirmarImpacto=true`. Tras una modificación real notifica a los pasajeros.
Un viaje previamente confirmado vuelve a quedar **sin confirmar** y reinicia el
recordatorio, para que el conductor lo reconfirme según US17. Toda nueva
salida debe estar al menos a 12 horas, también si aún no estaba confirmado. Un viaje finalizado, cancelado o en progreso
no se puede reprogramar.

**Respuestas:** `200` ✅ cambio guardado · `400` fecha/hora ausentes o pasadas ·
`409` confirmación de impacto, plazo de confirmación insuficiente o estado no editable ·
`404` viaje inexistente/ajeno · `403` rol no permitido.

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
Confirma un viaje con al menos 12 horas de anticipación respecto a la salida (US17).

| Path | Tipo |
|---|---|
| `id` | long |

**Respuestas**
- `200` ✅ viaje confirmado y pasajeros aceptados notificados; si ya estaba confirmado, responde `200` indicando que el viaje ya había sido confirmado.
- `409` si quedan menos de 12 horas para la salida y el viaje continúa sin confirmar: el viaje pasa a `VENCIDO` y se registra una penalidad `LEVE`.
- `409` viaje no `PROGRAMADO` o viaje ya ocurrido.
- `404` viaje inexistente o ajeno.

> El backend ejecuta automáticamente una revisión periódica de los
> viajes pendientes de confirmación. Cuando un viaje se encuentra
> entre 12 y 20 horas de su salida y aún no ha sido confirmado,
> el sistema genera una única notificación de recordatorio para
> el conductor.

#### `PUT /api/viajes/{id}/iniciar`
Pasa el viaje a `EN_PROGRESO` (ciclo de vida: `PROGRAMADO → EN_PROGRESO`).

| Path | Tipo |
|---|---|
| `id` | long |

**Respuestas:** `200` ✅ estado `EN_PROGRESO` (notifica a conductor y pasajeros aceptados); si ya estaba iniciado, `200` con `mensaje` · `409` viaje no `PROGRAMADO`, no confirmado o cuya hora de salida aún no llegó · `404` viaje inexistente o ajeno

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
- `200` ✅ viaje `CANCELADO` con notificación a todos los involucrados; si ya estaba cancelado, el conductor o el mismo autor pueden reintentar con `mensaje: "El viaje ya estaba cancelado"`, sin nuevas penalidades ni avisos
- `403` si no es el conductor ni un pasajero con solicitud aceptada
- `409` viaje no `PROGRAMADO` o ya ocurrió · `404` viaje inexistente o ajeno

> Penalidad aplicada por el servicio según anticipación: **> 24 h** sin penalidad · **4–24 h** `LEVE` · **< 4 h** `GRAVE`.

#### `DELETE /api/viajes/{id}`
Elimina un viaje propio.

| Path | Tipo |
|---|---|
| `id` | long |

**Respuestas:** `204` ✅ (notifica al conductor) · `409` viaje confirmado, histórico, con cualquier solicitud registrada o con penalidades; conserva actividad según cierre 11.1 · `404` viaje inexistente o ajeno · `403` sin token

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

**Respuestas:** `200` ✅ archivo PDF (`application/pdf`, `Content-Disposition: attachment`) · `400` `desde` posterior a `hasta` · `400` tipo inválido · `409` sin historial que exportar · `403` sin token

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
- `409` si existe **cualquier solicitud asociada**, independientemente de su estado o de la confirmación del viaje; se preserva la integridad e historial.

#### `GET /api/viajes/{id}/sugerencia-contribucion`
Sugerencia de contribución estimada para un viaje (US29).

| Path | Tipo |
|---|---|
| `id` | long — viaje en estado `PROGRAMADO` |

| Query | Tipo | Defecto | Detalle |
|---|---|---|---|
| `pasajeros` | int | – | Se usa el conteo de aceptados si es mayor que cero; solo si no hay aceptados se requiere indicar un valor mínimo de 1 |

**Fórmula:** `total = S/8.00 × 1.5` si es hora pico (06:00–10:00 o 17:00–21:00), si no `× 1.0`; `aporte = total / pasajeros`.

**Respuestas**
- `200` ✅ `{viajeId, origen, destino, dia, fecha, hora, pasajeros, esHoraPico, costoTotalEstimado, aporteEstimado, litrosGasolinaAprox, sugerenciaFavor, nota}`
- `400` `pasajeros` ausente o menor a 1 cuando el viaje aún no tiene aceptados
- `404` viaje no encontrado
- `409` viaje no `PROGRAMADO`
- `403` sin token

---

## Índice rápido

Inventario completo de los 49 mappings de controladores propios. No incluye Swagger,
OpenAPI ni endpoints internos de Spring. Las rutas conservan los contratos Angular.

| # | Método | Ruta | Permiso (además de propiedad/relación) | Controlador |
|---|---|---|---|---|
| 1 | POST | `/api/auth/login` | `Público` | AuthController |
| 2 | POST | `/api/auth/reenviar-codigo` | `Público` | AuthController |
| 3 | POST | `/api/auth/registro` | `Público` | AuthController |
| 4 | POST | `/api/auth/verificar` | `Público` | AuthController |
| 5 | GET | `/api/estadisticas` | `Autenticado` | EstadisticasController |
| 6 | GET | `/api/estadisticas/rutas` | `hasRole('CONDUCTOR')` | EstadisticasController |
| 7 | GET | `/api/estadisticas/semana` | `Autenticado` | EstadisticasController |
| 8 | GET | `/api/historial/pdf` | `Autenticado` | HistorialController |
| 9 | GET | `/api/metodos-compensacion` | `Autenticado` | MetodoCompensacionController |
| 10 | POST | `/api/metodos-compensacion` | `Autenticado` | MetodoCompensacionController |
| 11 | DELETE | `/api/metodos-compensacion/{id}` | `Autenticado` | MetodoCompensacionController |
| 12 | PUT | `/api/metodos-compensacion/{id}` | `Autenticado` | MetodoCompensacionController |
| 13 | GET | `/api/perfil` | `Autenticado` | PerfilController |
| 14 | PUT | `/api/perfil` | `Autenticado` | PerfilController |
| 15 | GET | `/api/perfil/cursos` | `Autenticado` | HorarioAcademicoController |
| 16 | POST | `/api/perfil/cursos` | `Autenticado` | HorarioAcademicoController |
| 17 | DELETE | `/api/perfil/cursos/{id}` | `Autenticado` | HorarioAcademicoController |
| 18 | POST | `/api/perfil/horarios/archivo` | `Autenticado` | HorarioAcademicoController |
| 19 | PUT | `/api/perfil/info-adicional` | `Autenticado` | PerfilController |
| 20 | GET | `/api/perfil/usuario/{usuarioId}` | `hasRole('CONDUCTOR')` | PerfilController |
| 21 | GET | `/api/rutas` | `hasRole('CONDUCTOR')` | RutaController |
| 22 | POST | `/api/rutas` | `hasRole('CONDUCTOR')` | RutaController |
| 23 | GET | `/api/rutas/resumen` | `hasRole('CONDUCTOR')` | RutaController |
| 24 | DELETE | `/api/rutas/{id}` | `hasRole('CONDUCTOR')` | RutaController |
| 25 | PUT | `/api/rutas/{id}` | `hasRole('CONDUCTOR')` | RutaController |
| 26 | GET | `/api/rutas/{rutaId}/viajes` | `hasRole('CONDUCTOR')` | ViajeController |
| 27 | POST | `/api/rutas/{rutaId}/viajes` | `hasRole('CONDUCTOR')` | ViajeController |
| 28 | GET | `/api/solicitudes` | `hasRole('PASAJERO')` | SolicitudController |
| 29 | GET | `/api/solicitudes/historial` | `hasRole('PASAJERO')` | SolicitudController |
| 30 | PUT | `/api/solicitudes/rechazar-multiples` | `hasRole('CONDUCTOR')` | SolicitudController |
| 31 | GET | `/api/solicitudes/recibidas/por-ruta` | `hasRole('CONDUCTOR')` | SolicitudesRecibidasUs14Controller |
| 32 | GET | `/api/solicitudes/recibidas/{solicitudId}/detalle` | `hasRole('CONDUCTOR')` | SolicitudesRecibidasUs14Controller |
| 33 | GET | `/api/solicitudes/resumen` | `hasRole('PASAJERO')` | SolicitudController |
| 34 | POST | `/api/solicitudes/viajes/{viajeId}` | `hasRole('PASAJERO')` | SolicitudController |
| 35 | PUT | `/api/solicitudes/{id}/aceptar` | `hasRole('CONDUCTOR')` | SolicitudController |
| 36 | PUT | `/api/solicitudes/{id}/cancelar` | `hasRole('PASAJERO')` | SolicitudController |
| 37 | PUT | `/api/solicitudes/{id}/rechazar` | `hasRole('CONDUCTOR')` | SolicitudController |
| 38 | GET | `/api/usuarios/me` | `Autenticado` | UsuarioController |
| 39 | PUT | `/api/usuarios/rol` | `Autenticado` | UsuarioController |
| 40 | GET | `/api/viajes/buscar` | `hasRole('PASAJERO')` | BusquedaController |
| 41 | GET | `/api/viajes/buscar/resumen` | `hasRole('PASAJERO')` | BusquedaController |
| 42 | DELETE | `/api/viajes/{id}` | `hasRole('CONDUCTOR')` | ViajeController |
| 43 | PUT | `/api/viajes/{id}` | `hasRole('CONDUCTOR')` | ViajeController |
| 44 | PUT | `/api/viajes/{id}/cancelar` | `hasAnyRole('CONDUCTOR', 'PASAJERO')` | ViajeController |
| 45 | PUT | `/api/viajes/{id}/completar` | `hasRole('CONDUCTOR')` | ViajeController |
| 46 | PUT | `/api/viajes/{id}/confirmar` | `hasRole('CONDUCTOR')` | ViajeController |
| 47 | PUT | `/api/viajes/{id}/iniciar` | `hasRole('CONDUCTOR')` | ViajeController |
| 48 | GET | `/api/viajes/{id}/sugerencia-contribucion` | `hasRole('PASAJERO')` | ViajeController |
| 49 | GET | `/api/viajes/{viajeId}/solicitudes` | `hasRole('CONDUCTOR')` | ViajeController |

## Reglas de hardening vigentes

- Creación y reprogramación requieren fecha/hora futuras y salida a >=12 h (409 si
  falta anticipación; 400 si fecha/hora ya ocurrió). Inicio requiere hora programada.
- PROGRAMADO sin confirmar vence automáticamente cuando restan <12 h, incluso
  salidas pasadas: VENCIDO + una penalidad LEVE y avisos a afectados, sin borrar historial.
- Cancelar conserva >24 h sin penalidad, 4..24 h LEVE, <4 h GRAVE. Se registra autor
  y fecha. Reintentos del mismo autor/conductor son 200 sin nuevos efectos; terceros 403.
- US20 es frecuencia histórica por día de semana; no una ventana móvil de siete días.
  Se requieren cinco viajes completados. Cancelaciones propias/terceros usan autor
  registrado, nunca penalidades; autor histórico desconocido no se atribuye.
- US21 cuenta **solo penalidades del conductor**, decisión confirmada por el responsable.
- PDF excluye PROGRAMADO futuro comparando fecha y hora, también hoy; filtros de
  fecha inclusivos, rol conductor/pasajero/todos y penalidades propias.
- Todos los listados paginados exigen page>=0, size 1..100; fuera de rango 400.
- Perfil actualiza nombre/apellidos en Usuario y Perfil en una transacción. Fechas
  académicas invertidas responden 400.
- Auth normaliza correo (trim/minúsculas), teléfonos E.164 (PE por defecto), valida
  dominios terminados en .edu o .edu.xx. El formato telefónico válido no prueba titularidad.
- OTP expira a los 15 min exactos, se guarda con BCrypt, permite cinco fallos y luego
  bloqueo 15 min; reenviar tiene cooldown de 60 s y no omite un bloqueo vigente.
  Los límites configurables por cuenta/IP responden 429 con Retry-After.
- Reenvío conserva AuthRespuesta, con `usuario: null` para proteger datos personales.
  Mantiene 404 cuenta inexistente y 409 cuenta verificada por compatibilidad.
- Códigos no se almacenan en avisos de plataforma. Correo usa outbox cifrado,
  entrega posterior al commit y reintento. Ver [operación](operacion-hardening.md).
- PDF/TXT conservan límite de 1 MiB; PDF comprueba firma, páginas, geometría, texto y
  streams expandidos, con proceso aislado, 256 MiB de heap y timeout de 60 s.
  TXT exige UTF-8 sin contenido binario. Los límites/formato son 400; PDF malformado,
  ilegible o fallo del proceso responde 409; saturación de procesadores responde 429.

## Transiciones y controles

| Recurso | Transición | Condición y autoridad |
|---|---|---|
| Viaje | Creación → PROGRAMADO sin confirmar | Conductor dueño de ruta no eliminada; salida futura a >=12 h |
| Viaje | PROGRAMADO → PROGRAMADO confirmado | Conductor propietario; >=12 h; repetición sin nuevos efectos |
| Viaje | PROGRAMADO → VENCIDO | Sin confirmar y <12 h, aunque la salida ya pasó; scheduler o confirmación tardía, una penalidad LEVE |
| Viaje | PROGRAMADO confirmado → EN_PROGRESO | Conductor propietario; hora de salida alcanzada; repetición sin nuevos efectos |
| Viaje | EN_PROGRESO → COMPLETADO | Conductor propietario; repetición sin nuevos efectos |
| Viaje | PROGRAMADO → CANCELADO | Conductor o pasajero aceptado, salida no pasada; reintenta solo conductor/autor; penalidad según 4/24 h |
| Ruta | ACTIVA / LLENA / VENCIDA | US09 calcula disponibilidad desde viajes, cupos y horario; no modifica ELIMINADA |
| Ruta | Estado vigente → ELIMINADA | Conductor propietario, confirmación de impacto cuando corresponde, ningún viaje EN_PROGRESO |
| Solicitud | Creación → PENDIENTE | Pasajero, viaje PROGRAMADO no pasado; no duplicar solicitud activa |
| Solicitud | PENDIENTE → ACEPTADA | Conductor propietario, viaje disponible y cupo; bloquea Viaje, crea un solo acuerdo BORRADOR; reintento 409 sin duplicar cupo/acuerdo |
| Solicitud | PENDIENTE → RECHAZADA | Conductor propietario, individual o lote; procesada previamente responde 409 |
| Solicitud | PENDIENTE → CANCELADA | Pasajero propietario; procesada previamente responde 409 |
| Solicitud | PENDIENTE / ACEPTADA → CANCELADA | Cancelación del viaje, conserva relación y marca participación efectiva |
| AcuerdoCompensacion | Creación → BORRADOR | Transacción de aceptación; relación única con Solicitud |

CONFIRMADO y RECHAZADO de AcuerdoCompensacion son valores persistibles del modelo,
pero no tienen transición expuesta por esta API. No se añaden endpoints ni se
eliminan acuerdos históricos al cancelar. La eliminación física de un viaje exige
PROGRAMADO sin confirmar, sin solicitudes ni penalidades.

Los reintentos individuales de solicitud ya aceptada/rechazada/cancelada conservan
el aviso REQUIRES_NEW de «acción ya realizada», agrupado por usuario/asunto durante
15 minutos; no repiten la transición. Un rechazo múltiple es atómico y valida
todos los estados dentro de la misma transacción.

La aceptación, cancelación, inicio, finalización, confirmación y vencimiento
comparten bloqueo de Viaje. Crear/editar/eliminar ruta bloquea Ruta primero y,
cuando corresponde, sus viajes ordenados por id; ninguna aceptación adquiere Ruta
después de Viaje. Las escrituras y avisos de plataforma comparten transacción;
los envíos SMTP ocurren después del commit mediante outbox.

Errores generales: ruta inexistente 404, método no soportado 405, tipo de contenido no soportado 415 y parámetros/archivo multipart faltantes 400. No se convierten en errores internos 500.
