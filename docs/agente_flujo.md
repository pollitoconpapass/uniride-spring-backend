# Agente UniRide: flujo, contratos y pruebas manuales

Implementación vigente: Java 21, Spring Boot 4.1.1, Spring AI 2.0.2 y Groq mediante API compatible con OpenAI, modelo `openai/gpt-oss-120b`. No requiere una clave de OpenAI. Configurar únicamente `GROQ_API_KEY` en el entorno privado.

## Componentes y descubrimiento

Java genera el texto visible a partir del resumen de la operación, las páginas consultadas, los reportes ejecutados o un catálogo de preguntas aclaratorias. El texto libre del modelo puede orientar una aclaración. El backend exige un recibo para comunicar el estado de una escritura e intercepta las excepciones de los callbacks antes del procesador de Spring AI.

`@SpringBootApplication` escanea `com.uniride`. Spring descubre `AgentConfig`, crea el `ChatClient` e inyecta los componentes por constructor. No se instancia la configuración manualmente.

```mermaid
flowchart TD
    C[AgentController] --> S[AgentService]
    S --> M[AgentConversationStore: RAM limitada]
    S --> CC[ChatClient / Groq]
    CC --> R[AgentToolRegistry: recibos por petición]
    S -->|Completa reportes omitidos| R
    R --> T[AgentTools]
    T --> Q[AgentQueryService: solo lectura]
    T --> A[EstadisticasService: reportes personales]
    T --> P[ConfirmationService: preparar]
    D[HistorialController: descarga con JWT] --> A
    H[ConfirmationController: JWT y UUID] --> E[ConfirmationService: confirmar/cancelar/consultar]
    E --> B[Services existentes: reglas de negocio]
    Q --> DB[Repositories]
    A --> DB
    P --> DB
    B --> DB
```

El modelo elige entre las trece herramientas registradas. Las consultas recuperan datos autorizados, la herramienta de historial prepara una descarga y las escrituras preparan confirmaciones que se ejecutan mediante una petición HTTP explícita. `AgentToolRegistry` conserva el resultado real de cada callback y construye la parte estructurada de la respuesta con los datos entregados por Java.

El modelo responde en texto natural. No se usa `.entity(ChatResponse.class)` ni `response_format=json_schema` durante tool calling: Groq no admite Structured Outputs junto con herramientas. No existe una herramienta `json` y la validación de herramientas permanece activada. Ver [diagnóstico y fuentes](auditoria-hardening-agente-ia.md).

## Autenticación y permisos

Todas las rutas `/api/agent/**` requieren Bearer JWT válido de una cuenta verificada. La identidad procede de Spring Security y del usuario actual de la base, nunca de argumentos del modelo. Las pruebas conservan el comportamiento HTTP existente de SecurityConfig: sin token, inválido o expirado se responde 403; no se cambia globalmente el contrato de seguridad.

| Herramienta | Rol actual requerido |
|---|---|
| consultarMisRutas, consultarMisViajes, crearRuta, crearViaje | CONDUCTOR |
| buscarViajes, consultarMisSolicitudes, solicitarUnirse | PASAJERO |
| consultarMisMetodosCompensacion, registrarMetodoCompensacion | Ambos |
| consultarMisEstadisticas, consultarMiFrecuenciaViajes | Ambos |
| consultarRankingMisRutas | CONDUCTOR |
| prepararDescargaHistorialPdf | Ambos |

La búsqueda de viajes respeta la restricción del controller tradicional a PASAJERO. El agente no cambia roles. La ejecución de confirmaciones comprueba nuevamente rol, propiedad, datos resumidos y reglas existentes.

## Conversación

```http
POST /api/agent/chat
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{"message":"¿Cuáles son mis rutas?"}
```

Respuesta de consulta (IDs y contenidos son ilustrativos):

```json
{
  "message":"Se encontró 1 resultado en las páginas consultadas.",
  "viajes":[],
  "rutas":[{"rutaId":13,"origen":"Surco","destino":"UPC","puntosReferencia":"Parque","zonasSinParada":null,"capacidadMaxima":4,"estado":"ACTIVA"}],
  "metodosCompensacion":[],
  "operacion":null,
  "resultados":[{"tipo":"consultarMisRutas","datos":{"items":[{"rutaId":13,"origen":"Surco","destino":"UPC","puntosReferencia":"Parque","zonasSinParada":null,"capacidadMaxima":4,"estado":"ACTIVA"}],"pagina":0,"tamano":10,"total":1,"hayMas":false}}],
  "advertencia":null,
  "conversationId":"2c54e29b-701e-44af-a745-87aa311b9661"
}
```

Los cinco campos originales se conservan. `resultados` es un sobre extensible: `tipo` identifica la herramienta y `datos` contiene su resultado Java. Las listas originales siguen disponibles para los clientes existentes. Las futuras tools pueden agregar tipos sin crear campos vacíos de funcionalidades que aún no existen.

Continuar sesión:

```json
{"message":"Muéstrame la página siguiente","conversationId":"2c54e29b-701e-44af-a745-87aa311b9661"}
```

Omitir `conversationId` inicia una sesión independiente. El ID es aleatorio y está vinculado al usuario; uno ajeno o desconocido devuelve 404. La sesión vive en RAM, conserva como máximo 20 mensajes (diez intercambios), admite 100 peticiones y expira tras 30 minutos sin actividad. Limpieza cada minuto. Se permiten cinco sesiones por usuario (se desplaza la más antigua libre) y mil en total por instancia. Un mensaje simultáneo en la misma sesión devuelve 409. El cambio de rol invalida su contexto (409). Reiniciar la instancia elimina la memoria, sin afectar confirmaciones persistidas. No hay endpoint para recuperar historias ajenas. No enviar contraseñas, JWT ni claves; los patrones de credenciales reconocidos se rechazan antes de llamar al proveedor.

Las consultas paginadas de rutas, viajes, solicitudes y métodos devuelven diez elementos por página y `hayMas`. La primera página es cero y se admite hasta la página 10000. La selección para escribir filtra todos los criterios en SQL y comprueba dos coincidencias para detectar ambigüedad global. La fecha se aplica antes de limitar. Las rutas muestran el estado persistido.

## Reportes de viajes por chat

Las herramientas de reportes permiten consultar estadísticas del usuario autenticado y preparar la descarga de su historial en PDF. Envía una pregunta en lenguaje natural a `POST /api/agent/chat`. Los endpoints tradicionales siguen disponibles para consultar los datos directamente.

Groq puede finalizar correctamente con texto aunque haya omitido una herramienta solicitada. Antes de este ajuste, una petición explícita como «¿Cuáles son mis rutas más utilizadas como conductor?» podía terminar con la aclaración genérica y `resultados` vacío. Tras una respuesta final válida de Groq, Java reconoce intenciones explícitas de reportes en el mensaje actual y ejecuta los callbacks de reportes que falten. Conserva los recibos que ya existen, aplica el presupuesto y los permisos del mismo turno y arma la respuesta sin otra llamada a Groq.

La detección admite expresiones en español con y sin tildes y combinaciones claras de reportes. Las peticiones ambiguas, las referencias que dependen solo de turnos anteriores, el listado de rutas, la búsqueda de viajes y las escrituras siguen el flujo habitual. Los fallos del proveedor siguen su propio manejo de errores.

| Historia y herramienta | Servicio reutilizado | Endpoint tradicional | Roles activos |
|---|---|---|---|
| US19 `consultarMisEstadisticas` | `EstadisticasService.resumen()` | `GET /api/estadisticas` | CONDUCTOR y PASAJERO |
| US20 `consultarMiFrecuenciaViajes` | `EstadisticasService.analisisSemanal()` | `GET /api/estadisticas/semana` | CONDUCTOR y PASAJERO |
| US21 `consultarRankingMisRutas` | `EstadisticasService.rankingRutas()` | `GET /api/estadisticas/rutas` | CONDUCTOR |

- **US19.** Devuelve `viajesComoConductor`, `viajesComoPasajero`, `viajesCancelados` y `penalidadesAcumuladas`. Los dos primeros campos cuentan viajes completados. Java suma ambos para presentar el total. La consulta admite historiales vacíos y conserva los ceros devueltos por el servicio.
- **US20.** Agrupa el historial de viajes completados por día de la semana. Con al menos cinco viajes, `viajesPorDia` contiene los siete días, incluidos los de frecuencia cero. También permite consultar un día concreto o identificar los días de mayor frecuencia. Con menos de cinco, `suficientesDatos` es `false`, `viajesPorDia` queda vacío y se presenta el `mensaje` del servicio. `canceladosPorMi` y `canceladosPorTerceros` se conservan en ambos casos.
- **US21.** El callback valida el rol activo antes de invocar el servicio, que requiere cinco viajes completados como conductor. Si se invoca con rol `PASAJERO`, el chat devuelve HTTP 403. Con suficientes datos, cada entrada del `ranking` incluye `origen`, `destino`, `cantidad` de viajes completados y `penalidades` del conductor asociadas a esa combinación. Con menos de cinco se devuelve `suficientesDatos: false`, el `mensaje` de negocio y un ranking vacío.

Las estadísticas de US19, US20 y US21 abarcan todo el historial. Si la pregunta solicita una semana concreta, los últimos siete días u otro período para estas estadísticas, el agente presenta los datos con su alcance histórico e informa que la consulta carece de filtros por fechas. Las consultas de reportes son de solo lectura y una petición exclusiva de reportes devuelve `operacion: null`.

Cada consulta queda en `resultados` con el nombre de la herramienta en `tipo` y el DTO de estadísticas en `datos`. `message` resume esos datos e indica su alcance histórico. Cuando hay datos suficientes, el resumen de frecuencia muestra el desglose por día y destaca los días con más viajes. El resumen del ranking destaca las rutas con más viajes y las que registran más penalidades, y el cliente puede leer el ranking completo en `datos`. Un turno puede reunir varios reportes bajo el mismo `conversationId`.

Ejemplo de respuesta con cifras ilustrativas para `{"message":"¿Cuántos viajes he completado?"}`.

```json
{
  "message": "Has completado 12 viajes (8 como conductor y 4 como pasajero). También registras 2 viajes cancelados y 1 penalidad acumulada. Los reportes consultan todo tu historial y no admiten filtros por fechas.",
  "viajes": [],
  "rutas": [],
  "metodosCompensacion": [],
  "operacion": null,
  "resultados": [
    {
      "tipo": "consultarMisEstadisticas",
      "datos": {
        "viajesComoConductor": 8,
        "viajesComoPasajero": 4,
        "viajesCancelados": 2,
        "penalidadesAcumuladas": 1
      }
    }
  ],
  "advertencia": null,
  "conversationId": "2c54e29b-701e-44af-a745-87aa311b9661"
}
```

### Descarga del historial en PDF (US22)

`prepararDescargaHistorialPdf` está disponible para `CONDUCTOR` y `PASAJERO`. El chat devuelve un descriptor de descarga en `resultados`, con `tipo: "prepararDescargaHistorialPdf"`. El enlace apunta a `GET /api/historial/pdf`. El archivo se genera al ejecutar ese GET con el JWT del usuario y con los filtros del descriptor. La petición exclusiva de PDF es de solo lectura y devuelve `operacion: null`. Los bytes del PDF permanecen en la respuesta del endpoint autenticado.

Java interpreta el período y el tipo de viaje del mensaje actual. Los períodos relativos se calculan con la fecha de `America/Lima`.

| Petición | Límites inclusivos |
|---|---|
| Todo mi historial | Sin límites de fecha |
| Últimos 30 días | Desde hace 29 días hasta hoy |
| Mes pasado | Del primer al último día del mes calendario anterior |
| Este mes | Del primer día del mes actual hasta hoy |
| Octubre de 2026 | Del 1 al 31 de octubre de 2026 |
| Desde 2026-10-01 hasta 2026-10-15 | Del 1 al 15 de octubre de 2026 |

`tipo` admite `todos`, `conductor` y `pasajero`. Indica cómo participó el usuario en sus viajes y su valor predeterminado es `todos`. El enlace incluye siempre `tipo` y omite `desde` o `hasta` cuando falta ese límite. Ante un período ambiguo o inválido, el agente pide aclaración y omite el resultado PDF y su enlace. Por ejemplo, «último mes» requiere elegir entre el mes calendario pasado y los últimos 30 días. Las demás consultas válidas del mismo turno se conservan.

Ejemplo del elemento de `resultados` para «Quiero descargar mi historial de viajes en PDF de octubre de 2026».

```json
{
  "tipo": "prepararDescargaHistorialPdf",
  "datos": {
    "nombreArchivo": "historial-viajes.pdf",
    "urlDescarga": "/api/historial/pdf?desde=2026-10-01&hasta=2026-10-31&tipo=todos",
    "desde": "2026-10-01",
    "hasta": "2026-10-31",
    "tipo": "todos",
    "mensaje": "Preparé la solicitud para descargar tu historial de viajes del 1 de octubre de 2026 al 31 de octubre de 2026. El PDF se generará cuando solicites la descarga autenticada."
  }
}
```

`urlDescarga` es una ruta relativa al origen del backend y solo contiene los filtros de la descarga. El cliente envía el JWT en el encabezado `Authorization: Bearer <token>` de la petición GET. `/api/historial/pdf` identifica al usuario autenticado y genera el PDF con sus datos. Un GET válido devuelve `200` con `Content-Type: application/pdf` y `Content-Disposition: attachment`. Si el historial no tiene filas para esos filtros, devuelve `409`. Ante un JWT ausente o inválido, devuelve `403`. Ver [contrato del endpoint tradicional](endpoints.md#módulo-5--historial-y-estadísticas).

La integración pendiente de Angular podrá mostrar un botón «Descargar PDF» para el resultado de US22 y hacer un GET autenticado con `responseType: 'blob'`. `nombreArchivo` permite guardar el archivo con el nombre que indica el backend.

## Preparación y confirmación

```json
{"message":"Crea un viaje el 25 de noviembre de 2026 a las 08:00 en mi ruta de Surco"}
```

Si faltan datos o hay varias coincidencias, no se crea ninguna operación de negocio. El servidor puede devolver `operacion.estado` como `DATOS_FALTANTES`, `AMBIGUO`, `NO_ENCONTRADO` o `ERROR`. Una pregunta sin tool conserva listas vacías y `operacion:null`.

Cuando los datos son válidos, `operacion` es:

```json
{
  "estado":"CONFIRMACION_REQUERIDA",
  "id":null,
  "detalle":"Programar un viaje desde Surco hacia UPC el 2026-11-25 a las 08:00, capacidad 4. Referencias: Parque. ¿Confirmas esta operación?",
  "confirmacion":{
    "id":"f081920f-85ea-4f48-aafa-5abb11ae0077",
    "tipo":"CREAR_VIAJE",
    "estado":"PENDIENTE",
    "resumen":"Programar un viaje desde Surco hacia UPC el 2026-11-25 a las 08:00, capacidad 4. Referencias: Parque.",
    "parametros":{"fecha":"2026-11-25","hora":"08:00:00"},
    "expira":"2026-11-21T17:10:00Z",
    "resultado":null
  }
}
```

El frontend muestra `resumen` y botones Confirmar/Cancelar. El identificador y la expiración proceden del backend. La confirmación caduca a los diez minutos; se permiten veinte pendientes vigentes por usuario. Preparar solamente inserta metadatos de confirmación, sin rutas, viajes, solicitudes, métodos ni notificaciones de negocio.

```http
POST /api/agent/confirmaciones/f081920f-85ea-4f48-aafa-5abb11ae0077/confirmar
Authorization: Bearer <token>
```

No enviar cuerpo ni query parameters. El servidor utiliza exclusivamente los parámetros guardados; cualquier cuerpo no vacío, incluso `{}`, se rechaza con 400. Decir «sí» al modelo no ejecuta nada.

La respuesta es `ConfirmacionResumen`:

```json
{
  "id":"f081920f-85ea-4f48-aafa-5abb11ae0077",
  "tipo":"CREAR_VIAJE",
  "estado":"EJECUTADA",
  "resumen":"Programar un viaje desde Surco hacia UPC el 2026-11-25 a las 08:00, capacidad 4. Referencias: Parque.",
  "parametros":{"fecha":"2026-11-25","hora":"08:00:00"},
  "expira":"2026-11-21T17:10:00Z",
  "resultado":{"estado":"EXITO","id":42,"detalle":"Viaje programado para el 2026-11-25 a las 08:00.","confirmacion":null}
}
```

Se bloquea la confirmación propia y se ejecuta negocio más recibo en una sola transacción. Repetir el mismo ID ejecutado devuelve el resultado almacenado. No se crea una segunda operación. No hay reintentos automáticos de escrituras. Ante un fallo de conexión con resultado incierto, consultar primero el mismo ID; no preparar una acción nueva suponiendo que falló.

```http
GET /api/agent/confirmaciones/{id}
POST /api/agent/confirmaciones/{id}/cancelar
```

Ambos requieren JWT del propietario. Cancelar no recibe cuerpo ni parámetros. Devuelve el mismo sobre con `estado:"CANCELADA"`, `resultado:null`; cancelar otra vez no ejecuta nada. Cancelar una ejecutada o confirmar una cancelada devuelve 409. Una confirmación expirada devuelve 410 y su sobre con `estado:"EXPIRADA"`, `resultado:null`. Una ajena/inexistente da 404. Cambio de rol incompatible: 403. Cambio de ruta, destino de perfil, horario o estado frente al resumen: 409, preparar de nuevo. Fallo de reglas de negocio: 400/409 según la regla, rollback conjunto y confirmación pendiente.

Los tipos implementados son `CREAR_RUTA`, `CREAR_VIAJE`, `SOLICITAR_UNIRSE` y `REGISTRAR_METODO`.

## Errores y recuperación

Error de proveedor sin resultados previos:

```json
{"mensaje":"El proveedor rechazó la respuesta o la llamada a una herramienta. No se reintentó la conversación.","campos":{"codigo":"PROVIDER_REJECTED"}}
```

| HTTP | Situación |
|---|---|
| 400 | Mensaje vacío/mayor a 1000, UUID mal formado, parámetros inválidos o credenciales en texto |
| 403 | JWT ausente/inválido/expirado o permiso insuficiente |
| 404 | Sesión/confirmación ajena, desconocida o sesión caducada |
| 409 | Sesión ocupada/cambio de rol, confirmación cancelada o datos/reglas cambiados |
| 410 | Confirmación expirada |
| 429 | Cuota del proveedor o límite de conversaciones |
| 502 | Herramienta desconocida/rechazada, respuesta inválida/vacía/incompleta |
| 503 | Proveedor no disponible, credencial Groq rechazada o conexión fallida |
| 504 | Timeout del proveedor |

Si la herramienta ya produjo un recibo antes del fallo del proveedor, se devuelve HTTP 200 con los datos existentes y `advertencia:{"codigo":"PROVIDER_REJECTED","mensaje":"..."}`. La confirmación pendiente sigue disponible en `operacion.confirmacion`. El cliente debe mostrar la advertencia y usar el estado real del backend. Nunca inferir éxito de escritura a partir de `message`. Cada turno permite como máximo ocho llamadas y una preparación; las llamadas idénticas del mismo turno usan su recibo. El cliente Groq no reintenta automáticamente; el timeout de cada solicitud es 30 segundos.

## Swagger y Postman

1. Arrancar con configuración privada de PostgreSQL, JWT y `GROQ_API_KEY`, tras revisar migraciones.
2. Iniciar sesión con una cuenta verificada mediante `/api/auth/login` y copiar el token de su respuesta al botón Authorize de Swagger (`/api/swagger-ui.html`) o al Bearer de Postman.
3. CONDUCTOR: «Consulta mis rutas», «Consulta mis viajes».
4. PASAJERO: «Busca viajes disponibles el viernes a las 07:00», «Consulta mis solicitudes».
5. Ambos: «Consulta mis métodos de compensación».
6. «Crea un viaje en mi ruta de Surco»: debe pedir fecha/hora, sin crear nada.
7. Preparar una escritura en una base local aislada, revisar el resumen y confirmar/cancelar mediante sus endpoints.
8. Para una prueba real con Groq sobre Supabase, utilizar únicamente consultas. No confirmar escrituras ni cambiar datos.

Para probar reportes, abre `POST /api/agent/chat` en Swagger, elige **Try it out** y ejecuta cada cuerpo por separado. Comprueba `message`, `conversationId`, `operacion: null` y el contenido de `resultados`.

```json
{"message":"¿Cuántos viajes he completado como conductor y como pasajero?"}
```

Busca `tipo: "consultarMisEstadisticas"` y compara los totales de `datos` con `GET /api/estadisticas`.

```json
{"message":"¿Cuántos viajes he realizado los lunes y qué día suelo viajar más?"}
```

Busca `tipo: "consultarMiFrecuenciaViajes"`. Con un historial suficiente, comprueba los siete días de `datos.viajesPorDia`, incluido el lunes.

```json
{"message":"¿Cuáles son mis rutas más utilizadas como conductor?"}
```

```json
{"message":"Muéstrame el ranking de mis rutas y cuáles tienen más penalidades."}
```

Busca `tipo: "consultarRankingMisRutas"`. Con un conductor que tenga al menos cinco viajes completados, compara `datos.ranking` con `GET /api/estadisticas/rutas` y verifica `origen`, `destino`, `cantidad` y `penalidades` de cada entrada. Con un conductor por debajo del mínimo, comprueba `datos.suficientesDatos: false`, `datos.mensaje` y el ranking vacío. Un pasajero que pide el ranking recibe HTTP 403 cuando se ejecuta el callback.

```json
{"message":"¿Cuántos viajes he completado y qué rutas tienen más penalidades?"}
```

Con un conductor, comprueba que `resultados` contiene un resultado de estadísticas y otro de ranking y que `message` utiliza los datos de ambos. Prueba también `{"message":"¿Cuál fue mi frecuencia de viajes en los últimos siete días?"}` para comprobar que la respuesta indica el alcance histórico y la limitación temporal.

### Probar el historial PDF

1. Autoriza Swagger con el JWT de un usuario verificado. Abre `POST /api/agent/chat` y prueba uno de estos cuerpos.

   ```json
   {"message":"Quiero descargar todo mi historial de viajes en PDF"}
   ```

   ```json
   {"message":"Quiero descargar mi historial de viajes en PDF de octubre de 2026"}
   ```

   ```json
   {"message":"Quiero descargar mi historial de viajes en PDF del mes pasado como pasajero"}
   ```

   ```json
   {"message":"¿Cuántos viajes he completado? También quiero descargar mi historial de viajes en PDF de los últimos 30 días."}
   ```

2. Busca `tipo: "prepararDescargaHistorialPdf"` y revisa `datos.urlDescarga`, `datos.nombreArchivo` y los filtros. En la petición combinada también se devuelve `tipo: "consultarMisEstadisticas"`.
3. Abre `GET /api/historial/pdf` en Swagger y copia los filtros de `datos`. Deja en blanco los límites de fecha que vengan vacíos. Ejecuta con el mismo JWT y verifica `200`, el tipo `application/pdf` y el archivo descargable. Con un historial vacío para los filtros, comprueba `409`.

Pruebas automáticas sin Groq ni Supabase: `mvnw.cmd clean test`, `mvnw.cmd verify`. PostgreSQL temporal: `powershell -File scripts/test-agent-postgres.ps1` (requiere binarios PostgreSQL; acepta `-PostgresBin`). El script crea una base nueva limitada a loopback, fuerza la configuración de pruebas, ejecuta `-Ppostgres-it test`, detiene el clúster y conserva sus archivos temporales para inspección. Nunca ejecutar `flyway clean`.
