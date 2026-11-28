# Módulo de agente (Spring AI)

Asistente de chat de UniRide. El routing es **determinista en Java**: antes de llamar al modelo, los
clasificadores de `app/intent` y `app/clarification` deciden qué tools se habilitan en el turno y qué
operaciones se preparan. El modelo solo elige entre esas tools y redacta la explicación.

```
mensaje ─► AgentService ─► clasificadores (Java) ─► AgentToolRegistry ─► tools ─► ChatResponse
           (turno,          (rol, confirmación,     (filtra por rol      (leen la BD   (tarjetas,
            memoria, PDF)    aclaración, tema)       e intención)         del proyecto) datos y estado)
```

Se mantiene el patrón Routing de la plantilla del curso, pero el router no es un LLM: en UniRide la
confirmación en dos pasos, los permisos y los datos que se muestran no pueden depender de una decisión
generativa. La sección [Diferencias con la plantilla](#diferencias-con-la-plantilla-del-curso) explica
qué se adaptó y por qué.

## Estructura

```
agent/
├─ core/                        MOTOR DEL CHAT: no contiene reglas de negocio
│  ├─ AgentController           /agent/chat
│  ├─ AgentService              orquesta: clasificadores, prompt, tools, memoria y PDFs
│  ├─ AgentConfig               construye el ChatClient y carga resources/prompts/common.md
│  ├─ AgentDefinition           cómo se describe un agente (nombre, descripción, tools)
│  ├─ AgentConversationStore    sesiones, propiedad, TTL, límites y memoria
│  ├─ AgentToolRegistry         registro de tools: filtrado por rol e intención, recibos y presupuesto
│  ├─ AgentProviderException    error controlado del proveedor
│  ├─ GroqHttpDiagnostics       diagnóstico HTTP del proveedor
│  └─ voice/
│     └─ TranscripcionService   audio → texto (Whisper)
└─ app/                         LO QUE CADA EQUIPO CAMBIA
   ├─ AgentCatalog              la lista de agentes (un @Bean AgentDefinition por agente)
   ├─ AgentQueryService         lecturas paginadas de solo lectura para las tools
   ├─ confirmation/             propuestas persistidas y botones Confirmar / No
   ├─ clarification/            aclaraciones estructuradas (formulario de campos faltantes)
   ├─ intent/                   reconocimiento en Java de confirmación, tema y operación
   ├─ summary/                  resúmenes de resultados que escribe el servidor
   ├─ voice/
   │  ├─ AgentVozController     /agent/voz: audio → texto → agente → respuesta
   │  └─ TextoHablado           resumen hablado a partir de los datos verificados
   ├─ tools/                    las tools: una clase por agente
   └─ dto/                      lo que se envía al frontend

resources/
└─ prompts/
   └─ common.md                 prompt de sistema común a todos los agentes
```

## Agregar un agente nuevo

1. Crea su clase de tools en `app/tools` (si necesita datos) con métodos `@Tool`.
2. Agrega un `@Bean AgentDefinition` en `AgentCatalog` con un `@Order` nuevo.
3. Escribe su sección en `common.md` o, cuando exista router LLM, un `<nombre>.md` propio.

La `description` del `AgentDefinition` se publica en la sección `# Temas` del prompt de sistema: es el
alcance que ve el modelo hoy y la clave de routing cuando se adopte un router generativo.

## Tools y datos

- Una tool es un método `@Tool` con una `description` clara: el modelo decide con ella cuándo llamarla.
- Recibe el `ToolContext` como último parámetro cuando necesita contexto que Java ya validó
  (filtros de PDF, filtros de contribución, selección de ruta).
- Si falla, lanza una excepción con un mensaje claro: el modelo lo recibe y se lo explica al usuario.
- Los datos que deben verse en pantalla se guardan en `AgentToolRegistry.Turn` y viajan en
  `ChatResponse.resultados` como `ResultadoAgente(tipo, datos)`. El modelo no los repite.
- Las listas heredadas (`viajes`, `rutas`, `metodosCompensacion`) siguen disponibles para el frontend.

## Cómo se evita que el agente invente

- El modelo de cada turno ve solo las tools de su rol y de la intención reconocida.
- Los clasificadores no se pisan: si el mensaje pertenece a otro dominio evidente (horario, curso,
  perfil, gustos, rol, días de la semana) y no hay vocabulario de compensación, `CompensationIntent`
  devuelve OTRA y el turno sigue al modelo. `AgentDomainGuardTest` mantiene ese contrato.
- Cuando hay datos, el `message` lo escribe el servidor (`summary/`, `HistorialPdfIntent`,
  `ContribucionIntent`), no el modelo.
- Sin recibo, una pregunta del modelo se devuelve tal cual; solo se sustituyen las afirmaciones
  de éxito no respaldadas («quedó programado») por el mensaje neutro.
- Las escrituras necesitan dos pasos: la tool `preparar*` deja una propuesta persistida y solo
  `POST /agent/confirmaciones/{id}/confirmar` (o un «sí» exacto en el chat) la ejecuta.
  `ConfirmationIntent` resuelve la confirmación en Java antes de llamar al modelo.
- Los filtros del PDF de historial y los argumentos de compensación se derivan en Java y viajan por
  `ToolContext`; nunca los propone el modelo.
- Si el modelo ya eligió preparar una ruta, viaje o solicitud, Java acepta la operación y conserva
  solo los argumentos que aparecen en el mensaje. Un rechazo o una consulta («¿cómo elimino…?»,
  «muéstrame…») no se convierte en propuesta. Las preparaciones inferidas y los bloqueos se
  registran en el log para medir las frases que el reconocimiento estructurado no cubre.
- El mensaje del historial que recibe el proveedor se limita a los últimos ocho mensajes y los
  resultados que parecen credenciales no se envían ni se guardan.

## Confirmación (equivalente a la compra de la plantilla)

```
tool preparar* ─► ConfirmationService (BD) ─► propuesta PENDIENTE ─► botón o "sí" en el chat
                                                        │
                                                        └─► confirmar: ejecuta el servicio y guarda el resultado
                                                            cancelar: deja la operación sin ejecutar
```

- `confirmation/` guarda tipo de operación, parámetros exactos, resumen, snapshot, expiración y estado.
- `ConfirmationController` expone `GET`, `POST .../confirmar` y `POST .../cancelar`, autenticados.
- Si el resultado es incierto (fallo de transacción), no se reintenta: se responde 503 y se pide
  consultar el estado con el mismo identificador.

## Correcciones sobre el viaje anterior

Si el mensaje se refiere al último viaje («a ese viaje ponlo a las 7»), `IntencionAclarable` usa el
contexto de la conversación (origen, fecha y hora) en lugar de volver a preguntar. Si la propuesta
todavía está pendiente, `ConfirmationService.reprogramarPropuestaViaje` la cancela y prepara otra con
la misma ruta y el nuevo horario, en la misma transacción. Una hora suelta hereda el periodo del viaje
anterior («a las 7» tras un viaje de tarde es 19:00), salvo que se indique «de la mañana/tarde».

## Voz

La voz es otra forma de entrar y salir del mismo chat: **no cambia el agente**.

```
audio ─► TranscripcionService.transcribir ─► AgentService.chatVoz ─► ChatResponse ─► TextoHablado
         (Whisper en el backend)              (el mismo flujo)                       (lo lee el navegador)
```

| Pieza                 | Dónde                | Qué hace                                                                                   |
| --------------------- | -------------------- | ------------------------------------------------------------------------------------------ |
| `TranscripcionService`| `core/voice`         | Valida formato y tamaño y transcribe con `TranscriptionModel`. No conoce el dominio.       |
| `AgentVozController`  | `app/voice`          | `POST /agent/voz` (multipart: `audio`, `conversationId`). Devuelve `VozChatResponse`.      |
| `TextoHablado`        | `app/voice`          | Arma el texto hablado con los datos verificados; el navegador lo reproduce.                 |
| Botón **Hablar**      | frontend Angular     | Graba con `MediaRecorder`, envía el audio y reproduce con `speechSynthesis`.                |

**Configuración** (`application.yml`): `spring.ai.openai.audio.transcription` con
`whisper-large-v3-turbo`, idioma `es` y vocabulario del dominio en `prompt`. El proveedor es Groq,
que no expone TTS: la síntesis de voz ocurre en el navegador (`es-PE`), sin costo ni clave extra.
El audio no se guarda ni se registra en logs.

**Confirmaciones por voz:** una transcripción exacta de «sí» o «no» se resuelve con el mismo
`ConfirmationIntent` que el texto, así que la UI debe pedir una respuesta explícita y no leer
opciones ambiguas.

**Requisitos:** HTTPS o `localhost` para que el navegador permita el micrófono.

## Estructura del prompt

`common.md` se carga al construir el `ChatClient` y se antepone a todos los turnos. Se compone de:

| Sección             | Contenido                                                                 |
| ------------------- | ------------------------------------------------------------------------- |
| `# Rol`             | quién es el asistente, tono y qué construye Java                          |
| `# Reglas generales`| uso de tools, escrituras en dos pasos, privacidad, formato de respuesta    |
| `# Temas`           | generada desde `AgentCatalog`: nombre y `description` de cada agente       |

La fecha y el rol activo no viven en el prompt: `AgentService` los inyecta en el mensaje del turno.
Los archivos `<agente>.md` de la plantilla quedan pendientes: sin router LLM nunca se cargarían por
separado y varios quedarían vacíos. Cuando se adopte `RoutingWorkflow`, cada `AgentDefinition` recibirá
su archivo y las secciones `# Tarea`, `# Tools`, `# Reglas` y `# Cuando algo falla`.

Lo que debe cumplirse siempre (permisos, confirmación en dos pasos, mensajes con datos) está en el
código, no en el prompt.

## Diferencias con la plantilla del curso

| Plantilla                                   | UniRide                                                                                      |
| ------------------------------------------- | -------------------------------------------------------------------------------------------- |
| `RoutingWorkflow` con router LLM            | Routing determinista en Java (`intent/` + `clarification/`). Evita que una mala decisión del modelo habilite tools de otro rol o confirme una escritura. |
| `ResponseData.of(context)`                  | `AgentToolRegistry.Turn` + `ResultadoAgente` + `ChatResponse`.                            |
| `core/knowledge` (RAG de políticas)         | No aplica: UniRide no tiene agente de políticas ni PDFs de conocimiento.                     |
| `VoiceService` con `tts-1` en el servidor   | STT en el backend y TTS en el navegador: Groq no ofrece TTS y así no se agrega otra clave.    |
| `PurchaseController` (compra)               | `confirmation/ConfirmationController` (confirmar/cancelar cualquier operación preparada).     |
| `POST /agent/voice` con `speak` y base64    | `POST /agent/voz` con `VozChatResponse` (`texto`, `textoHablado`, `respuesta`).               |

## Límites

- El modelo decide qué tool llamar entre las habilitadas; el prompt y las `description` son lo que más importa.
- Un mensaje compuesto («mis viajes y cámbiame el rol») puede usar varias tools en el mismo turno.
- Las conversaciones se guardan por usuario, con TTL y límites, y solo su dueño las ve.
- Una operación de escritura por mensaje; el resto se rechaza con un mensaje controlado.
