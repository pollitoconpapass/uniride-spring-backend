# Auditoría y hardening del agente UniRide

> Informe histórico de la fase de hardening. La [guía vigente del agente](agente_flujo.md#preparación-y-confirmación) describe también la confirmación y cancelación por chat incorporadas después de esta auditoría.

Fecha: 2026-11-21. Rama inicial y de trabajo: `fix/ia-hardening-base`.
Al comenzar había una modificación local del prompt en `AgentConfig.java`; se revisó y se integró su intención (JSON no es una herramienta) en la solución.

## Estado inicial y validación

Se revisaron README, documentación del agente, todas las clases del módulo, seguridad JWT, servicios invocados, configuración, POM, migraciones y pruebas. No existe SPEC.md.
Java 21, Boot 4.1.1, Spring AI 2.0.2, Groq `openai/gpt-oss-120b`.
Seis migraciones: V1/V2/V4/V5/V6 SQL y V3 Java (`db.migration`).
Contrato inicial: `POST /api/agent/chat`, JWT, petición `{ "message": "..." }`, respuesta con `message`, `viajes`, `rutas`, `metodosCompensacion`, `operacion`.

El primer intento de compilar falló antes de Maven: el wrapper PowerShell indexaba `Target[0]` cuando `.m2` no era un enlace. Se agregó una comprobación de nulo. Compilación inicial posterior: `mvnw.cmd -DskipTests compile`, BUILD SUCCESS; advertencia preexistente de MapStruct en ViajeMapper.

## Bloqueador prioritario: herramienta inexistente `json`

El 400 registrado por Groq demuestra que el modelo solicitó una herramienta no incluida en `request.tools`; la validación del proveedor la rechazó correctamente.
La configuración inicial no activaba explícitamente `response_format=json_schema`: `.entity(ChatResponse.class)` agrega instrucciones de conversión/esquema al prompt. Junto con el system prompt que exigía JSON y tool calling, introducía dos contratos de generación diferentes en la misma conversación. La interpretación de JSON como función es consistente con ese conflicto; sin la generación cruda del proveedor no se puede atribuir con certeza su decisión interna a un único componente.

La [documentación oficial de Groq](https://console.groq.com/docs/structured-outputs) indica que Structured Outputs no admite tool use. La [documentación de Spring AI](https://docs.spring.io/spring-ai/reference/api/structured-output.html) diferencia conversión estructurada y tool calling y advierte que la conversión es best effort. El [modelo GPT OSS en Groq](https://console.groq.com/docs/tool-use/overview) admite herramientas locales, pero no llamadas paralelas.

Corrección:

- El modelo usa herramientas y termina con texto natural. No se usa `.entity`, esquema de ChatResponse ni modo JSON durante esa llamada.
- `AgentToolRegistry` envuelve callbacks y conserva recibos por petición. Java construye `ChatResponse` con esos datos; el modelo no rellena IDs, listas ni estados.
- Se conserva cada herramienta registrada. No existe herramienta `json` ni se deshabilita validación alguna.
- Límite de ocho llamadas por turno, recibos de llamadas repetidas y cero reintentos automáticos del proveedor. Tiempo de espera acotado y herramientas paralelas desactivadas.
- Respuesta vacía, truncada, JSON inesperado o llamada inválida: error controlado. Si ya existen resultados de herramientas, se devuelven los recibos sin repetir la conversación.
- HTTP 502 para rechazo/formato/herramienta inválida; 503 para indisponibilidad/credencial del proveedor; 429 para cuota; 504 para timeout. Los detalles del SDK, claves y generación fallida no se envían al cliente.

## Hallazgos adicionales confirmados

| Problema | Causa | Corrección implementada |
|---|---|---|
| Cuatro tools escriben inmediatamente | La confirmación depende del modelo | Preparación y confirmación HTTP persistida |
| Consultar rutas puede escribir | Sincronización de estados en RutaService.listarMisRutas | Consultas del agente de solo lectura |
| Autorización incompleta por llamadas directas | Parte de los roles solo se comprueban en controllers | Verificar rol actual, propiedad y negocio en backend |
| ddl-auto update | Regresión al cambiar proveedor | validate y migración nueva |
| Búsquedas incompletas y ambigüedad falsa | Límites 20/100 antes de filtrar | SQL paginado y selección con todos los filtros |
| Memoria por correo sin expiración | Advisor en RAM sin gestión de sesiones | Sesiones aleatorias vinculadas al usuario y límites |
| Datos estructurados generados por LLM | DTO convertido desde texto del modelo | Recibos de tools y resultados extensibles |

No se realizaron llamadas a Groq ni conexiones a Supabase. Las pruebas de persistencia utilizan exclusivamente bases aisladas.

## Arquitectura y decisiones finales

Se conserva `AgentController → AgentService → ChatClient → AgentTools → Services → Repositories`.
Para las consultas, `AgentQueryService` proporciona lecturas paginadas sin efectos secundarios. Para escrituras, las tools llaman a `ConfirmationService.preparar*`; `ConfirmationController` recibe posteriormente la aprobación humana y `ConfirmationService` ejecuta los servicios existentes. No hay herramienta de confirmación.

`AgentToolRegistry` captura resultados del backend y proyecta los tipos conocidos a las listas heredadas. `ResultadoAgente(tipo, datos)` admite resultados futuros sin agregar DTO ficticios. `message`, listas, `operacion`, estados e IDs del contrato se construyen en Java. El texto visible utiliza el resumen/resultado de la operación, conteos paginados o preguntas de un catálogo; el modelo solo orienta la pregunta. Esto elimina también afirmaciones como «quedó programado» cuando no existe recibo.

Se interceptan excepciones de callbacks antes del procesador predeterminado de Spring AI: este convertía `ToolExecutionException.getMessage()` en texto para el modelo. Los errores de permisos se propagan como errores controlados y los errores internos se sustituyen por un código seguro; no se envían SQL ni detalles de infraestructura a Groq.

Los validadores puros extraídos de RutaService, ViajeService, SolicitudService y MetodoCompensacionService comparten las reglas ya existentes de publicación, salida futura, propiedad, disponibilidad y duplicados. La preparación invoca Jakarta Validation explícitamente. La lógica tradicional de notificaciones de solicitudes duplicadas se conserva, pero no se ejecuta durante una preparación.

## Confirmaciones y seguridad transaccional

- UUID aleatorio; usuario FK; tipo; parámetros exactos; resumen; snapshot del recurso; creación/expiración; estado y resultado persistidos en V7.
- TTL de diez minutos, máximo veinte pendientes vigentes por usuario. La preparación solo persiste metadatos, sin modificar entidades ni emitir notificaciones de negocio.
- Consulta, cancelación y confirmación bloquean por UUID **y propietario**. Una ajena es indistinguible de una inexistente (404).
- Antes de ejecutar se refresca y bloquea al usuario para comprobar el rol actual. Los recursos se bloquean y se comparan los campos resumidos; un cambio exige preparar de nuevo. Se revalidan las reglas del servicio.
- Para registrar métodos, el lock del usuario es exclusivo desde el inicio; dos UUID con el mismo tipo/descripción no superan concurrentemente `exists → save`. Las demás ramas usan un lock compartido compatible con las FK de notificaciones.
- La escritura y el recibo EJECUTADA pertenecen a la misma transacción. Duplicar el mismo UUID devuelve el resultado persistido. Cancelada: 409; expirada: 410 sin ejecutar; rol incompatible: 403; datos/reglas cambiados: 400/409.
- Las transacciones tienen timeout de diez segundos. Un fallo transaccional o de conexión incierto devuelve 503 `CONFIRMATION_RESULT_UNCERTAIN`: consultar el mismo UUID antes de reintentar, sin preparar una acción nueva. No hay reintento automático.
- Expiración efectiva por reloj antes de ejecutar, y estado EXPIRADA persistido al consultar/confirmar/cancelar. No hay borrado automático del historial.
- Se normaliza la precisión de instantes a microsegundos y los snapshots JSON a representaciones equivalentes; esto evita falsos cambios por IntNode/LongNode sin omitir ningún campo.

## Contratos HTTP

| Método y ruta pública | Resultado |
|---|---|
| POST `/api/agent/chat` | Conserva petición `message` y los cinco campos originales; agrega `conversationId` opcional, `resultados`, `advertencia` y `operacion.confirmacion` |
| GET `/api/agent/confirmaciones/{id}` | Estado y recibo propio; 410 si expiró |
| POST `/api/agent/confirmaciones/{id}/confirmar` | Ejecuta una pendiente propia o devuelve el recibo almacenado |
| POST `/api/agent/confirmaciones/{id}/cancelar` | Cancela una pendiente propia de forma idempotente |

Confirmar y cancelar rechazan cuerpos no vacíos y query parameters (400); el cliente no puede alterar parámetros al aprobar. Decir «sí» al modelo no ejecuta. Los ejemplos JSON de consulta, pregunta, preparación, éxito, cancelación, expiración y errores están en [agente_flujo.md](agente_flujo.md). Se conserva el 403 existente de SecurityConfig para JWT ausente, inválido o expirado; no se alteran endpoints tradicionales.

## Autorización, resultados y memoria

Las consultas de rutas/viajes requieren CONDUCTOR, búsqueda/solicitudes requieren PASAJERO y métodos admiten ambos roles, conforme a los controllers tradicionales. UsuarioService descarta autenticación anónima/no autenticada. Ninguna tool recibe IDs de usuario del modelo. Consultas SQL se filtran por propietario y aplican fecha antes del límite; dos coincidencias bastan para detectar ambigüedad global. Páginas de diez, máximo página 10000 documentado, con total y hayMas. Los endpoints frontend no cambian.

La memoria vive en RAM por UUID+usuario, con treinta minutos de inactividad, limpieza cada minuto, veinte mensajes, cien peticiones por sesión, cinco sesiones por usuario y mil por instancia. Un mensaje concurrente en la misma sesión devuelve 409. El rol cambiado invalida el contexto. Omitir conversationId crea una sesión independiente; un ID ajeno/desconocido devuelve 404. Reiniciar pierde conversación, pero conserva confirmaciones. No se almacenan correos como clave ni transcripts completos de tools.

Los patrones reconocidos de contraseñas, JWT, Bearer y claves se rechazan antes del proveedor; también se filtran resultados de tools y datos de confirmación. Esto incluye frases como «mi contraseña es …». La detección es una protección adicional, no una clasificación general de todo dato sensible posible. No se registran mensajes ni cuerpos de error del proveedor.

## PostgreSQL y actualización de una base existente

`ddl-auto: validate` reemplaza la regresión `update` introducida al migrar a Groq (commit histórico 01b1119). Se mantienen `clean-disabled: true` y `baseline-on-migrate: false`. V1–V6 no se modifican. V3 es una migración Java; V7 agrega únicamente `agent_confirmaciones`, sus restricciones e índice. La validación ejecutada de PostgreSQL se registra abajo.

Procedimiento de despliegue, **no ejecutado sobre Supabase**:

1. Obtener backup y probar su restauración en una base aislada. Revisar [operación y baseline](operacion-hardening.md).
2. Comprobar historial y checksums de V1–V6. Una base ya gestionada por Flyway no necesita otro baseline, repair ni reinicialización.
3. Revisar V7, permisos DDL del usuario de migraciones y espacio disponible. Arrancar la nueva versión en una ventana controlada: Flyway aplica V7 y Hibernate valida.
4. Verificar los siete registros exitosos del historial y la tabla nueva; probar primero consultas y una preparación/cancelación en la copia aislada.
5. Si la base antigua no tiene historial Flyway, seguir el procedimiento existente de backup, comparación completa y baseline explícito V1 **solo tras comprobar equivalencia**; después migrar V2–V7. No activar baseline automático ni editar checksums. La normalización histórica V3 requiere revisar colisiones de teléfonos/correos antes de aplicarse.

No usar `create`, `create-drop` o `update` en despliegue y nunca `flyway clean`. El create-drop de H2 existe exclusivamente en recursos de pruebas aisladas.

`scripts/test-agent-postgres.ps1` crea un directorio GUID y un clúster nuevo PostgreSQL en loopback, selecciona un puerto libre y fuerza las conexiones JPA/Flyway a esa base por propiedades JVM. No admite una URL de desarrollo. Detiene el clúster en finally y conserva archivos temporales para inspección; no hace borrados recursivos. En Windows se inicia pg_ctl oculto con salidas separadas para no retener la consola del servidor.

## Configurar Groq y probar manualmente

Copiar `.env.example` y completar `GROQ_API_KEY` privadamente, junto con las variables existentes de DB/JWT. La base es `https://api.groq.com/openai/v1`; modelo `openai/gpt-oss-120b`, temperatura 0.1. No se requiere una clave de OpenAI. El cliente usa cero reintentos, sin paralelismo y timeout de treinta segundos por solicitud; cada turno admite ocho llamadas y una preparación.

Usar Swagger `/api/swagger-ui.html` o Postman con login de una cuenta verificada y Bearer JWT. Probar rutas/viajes con conductor, búsqueda/solicitudes con pasajero y métodos con ambos. «Crea un viaje en mi ruta de Surco» debe preguntar fecha/hora. En la base aislada, revisar el resumen y confirmar/cancelar mediante sus endpoints. Ver ejemplos y secuencia completa en [agente_flujo.md](agente_flujo.md).

No se ejecutó una prueba real de Groq: la regresión está reproducida con el transporte real Spring AI/OpenAI contra un servidor HTTP simulado local. Una prueba real posterior sobre Supabase debe limitarse a consultas; las nuevas consultas del agente no sincronizan estados ni escriben.

## Limitaciones y extensión por los cuatro integrantes

- Memoria por instancia: no sobrevive reinicios ni se comparte entre réplicas. Para varias réplicas se necesita afinidad de sesión o un diseño posterior de almacenamiento compartido.
- Las confirmaciones históricas se conservan; se debe definir una política de retención antes de un volumen sostenido. Una pendiente caducada se marca EXPIRADA al acceder, y nunca se ejecuta aunque aún conserve PENDIENTE físicamente.
- Las rutas muestran su estado persistido; el agente no ejecuta la sincronización escrita del endpoint tradicional. La ejecución siempre vuelve a validar negocio.
- El texto visible usa mensajes controlados y preguntas de catálogo: prioriza veracidad de operaciones y datos sobre una narración libre. Los futuros módulos pueden ampliar preguntas al incorporar funciones reales.
- El límite de treinta segundos es por solicitud HTTP del proveedor; un turno con varias herramientas puede consumir varias solicitudes. No hay reintentos automáticos.
- La serialización de métodos del agente evita duplicados entre sus confirmaciones. No se refactoriza globalmente la concurrencia del endpoint tradicional de métodos ni se agrega una restricción unique a datos existentes sin preflight.
- Advertencias MapStruct preexistentes de ViajeMapper y deprecación de callbacks en Spring AI 2.0.2 no impiden el build. Una migración futura de esa API debe conservar el saneamiento y los recibos.
- No se implementaron perfil, horarios, reportes, gestión de solicitudes recibidas ni coordinación de viajes.

Cada integrante debe reutilizar identidad/rol desde SecurityContext, consultas acotadas y el protocolo compartido. Un tipo nuevo de operación requiere DTO concreto, validador puro, snapshot, ejecución transaccional y **nueva migración** para actualizar el CHECK de tipos, sin editar V7 aplicada. Añadir el nombre de preparación al límite por turno y no exponer confirmación como tool. Agregar pruebas de permisos, propiedad, parámetros, rollback y concurrencia; nunca depender de Groq/Supabase reales. Guía: [instrucciones_agente_spring_ai.md](instrucciones_agente_spring_ai.md).

## Pruebas y estado de la entrega

La primera suite completa detectó cuatro fallos de snapshots numéricos (443 pruebas ejecutadas); se corrigió la causa y pasaron las pruebas dirigidas. La siguiente suite completa pasó con 448 pruebas. Se añadieron después verificaciones de errores internos de tools y resultado transaccional incierto; el resultado final de clean test, verify y PostgreSQL se registra a continuación al concluirlos.

## Resultados finales ejecutados

| Validación | Resultado |
|---|---|
| `mvnw.cmd clean test` final | BUILD SUCCESS, 452 pruebas, cero fallos/errores/omisiones, 1 min 8 s |
| `mvnw.cmd verify` | BUILD SUCCESS, 452 pruebas, cero fallos/errores/omisiones; JAR ejecutable generado, 56.449 s |
| `powershell -File scripts/test-agent-postgres.ps1` | BUILD SUCCESS, 30 pruebas sobre PostgreSQL 18.6 temporal, cero fallos/errores/omisiones; Maven 32.195 s |
| Flyway/PostgreSQL | Esquema nuevo V1–V7, Hibernate validate; fixture de baseline V1 conservó filas, aplicó V2–V7 y segunda migración no hizo cambios |
| Seguridad y Git | diff --check sin errores, sin conflictos, cero coincidencias de claves/JWT/llaves privadas literales en archivos cambiados |

Cobertura específica: proveedor HTTP simulado (21 casos), JWT/HTTP/confirmación incierta (18), memoria (7), registro/límites/recibos (4), consultas unitarias y persistidas (5+5), confirmaciones transaccionales (24 en H2 y los mismos 24 en PostgreSQL), además de las tools y la suite preexistente. Se verifican rutas propias, búsqueda de viajes, viajes propios, métodos, datos faltantes, ambigüedad, formato inválido y herramienta inexistente. Las pruebas anteriores de tools se adaptaron a preparación, sin eliminarlas ni deshabilitarlas.

Los intentos iniciales del script PostgreSQL encontraron restricciones del sandbox, herencia de handles de consola en Windows y tratamiento de stderr de JVM como excepción PowerShell. Se corrigió el script y se completó la ejecución real indicada arriba. Todos los clústeres temporales iniciados se detuvieron; no se ejecutó ninguna operación sobre PostgreSQL/Supabase de desarrollo. Los logs finales están en la carpeta temporal de Windows (`uniride-agent-clean-test.log`, `uniride-agent-postgres-test.log`) y los reports de la suite rápida en `target/surefire-reports`.

Git permanece en `fix/ia-hardening-base`, con cambios sin staging y sin commits, push, PR o merges. Se conserva el laboratorio original en `docs/historico/instrucciones_agente_spring_ai_laboratorio.md`, claramente marcado como referencia anterior al hardening. Las tareas operativas pendientes son aplicar V7 tras revisión en el entorno elegido y realizar la prueba manual de solo lectura con Groq; ninguna se ejecutó sobre Supabase.

## Archivos modificados y nuevos (inventario exacto)

Modificados: 28. Nuevos: 27. Los archivos Java nuevos constituyen las nuevas clases/records/enums de esta entrega.

### Modificados

- `.env.example`
- `README.md`
- `docs/agente_flujo.md`
- `docs/instrucciones_agente_spring_ai.md`
- `mvnw.cmd`
- `src/main/java/com/uniride/agent/AgentConfig.java`
- `src/main/java/com/uniride/agent/AgentController.java`
- `src/main/java/com/uniride/agent/AgentService.java`
- `src/main/java/com/uniride/agent/dto/ChatRequest.java`
- `src/main/java/com/uniride/agent/dto/ChatResponse.java`
- `src/main/java/com/uniride/agent/dto/EstadoOperacion.java`
- `src/main/java/com/uniride/agent/dto/OperacionResumen.java`
- `src/main/java/com/uniride/agent/tools/MetodoCompensacionAgentTools.java`
- `src/main/java/com/uniride/agent/tools/RutaAgentTools.java`
- `src/main/java/com/uniride/agent/tools/ViajeAgentTools.java`
- `src/main/java/com/uniride/exceptions/GlobalExceptionHandler.java`
- `src/main/java/com/uniride/repositories/MetodoCompensacionRepository.java`
- `src/main/java/com/uniride/repositories/RutaRepository.java`
- `src/main/java/com/uniride/repositories/ViajeRepository.java`
- `src/main/java/com/uniride/services/MetodoCompensacionService.java`
- `src/main/java/com/uniride/services/RutaService.java`
- `src/main/java/com/uniride/services/SolicitudService.java`
- `src/main/java/com/uniride/services/UsuarioService.java`
- `src/main/java/com/uniride/services/ViajeService.java`
- `src/main/resources/application.yml`
- `src/test/java/com/uniride/agent/tools/RutaAgentToolsTest.java`
- `src/test/java/com/uniride/agent/tools/ViajeAgentToolsTest.java`
- `src/test/java/com/uniride/services/PostgresMigracionIntegrationTest.java`

### Nuevos

- `docs/historico/instrucciones_agente_spring_ai_laboratorio.md`
- `docs/auditoria-hardening-agente-ia.md`
- `scripts/test-agent-postgres.ps1`
- `src/main/java/com/uniride/agent/AgentConversationStore.java`
- `src/main/java/com/uniride/agent/AgentProviderException.java`
- `src/main/java/com/uniride/agent/AgentQueryService.java`
- `src/main/java/com/uniride/agent/AgentToolRegistry.java`
- `src/main/java/com/uniride/agent/confirmation/ConfirmationController.java`
- `src/main/java/com/uniride/agent/confirmation/ConfirmationRepository.java`
- `src/main/java/com/uniride/agent/confirmation/ConfirmationService.java`
- `src/main/java/com/uniride/agent/confirmation/EstadoConfirmacion.java`
- `src/main/java/com/uniride/agent/confirmation/PendingConfirmation.java`
- `src/main/java/com/uniride/agent/confirmation/TipoOperacion.java`
- `src/main/java/com/uniride/agent/dto/AdvertenciaAgente.java`
- `src/main/java/com/uniride/agent/dto/ConfirmacionResumen.java`
- `src/main/java/com/uniride/agent/dto/PaginaAgente.java`
- `src/main/java/com/uniride/agent/dto/ResultadoAgente.java`
- `src/main/resources/db/migration/V7__confirmaciones_agente.sql`
- `src/test/java/com/uniride/agent/AgentConversationStoreTest.java`
- `src/test/java/com/uniride/agent/AgentHttpSecurityTest.java`
- `src/test/java/com/uniride/agent/AgentQueryPersistenceTest.java`
- `src/test/java/com/uniride/agent/AgentQueryServiceTest.java`
- `src/test/java/com/uniride/agent/AgentToolRegistryTest.java`
- `src/test/java/com/uniride/agent/GroqProviderIntegrationTest.java`
- `src/test/java/com/uniride/agent/confirmation/ConfirmationServiceIntegrationTest.java`
- `src/test/java/com/uniride/agent/confirmation/PostgresConfirmationIntegrationTest.java`
- `src/test/java/com/uniride/agent/tools/MetodoCompensacionAgentToolsTest.java`
