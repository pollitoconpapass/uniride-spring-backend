# Integración y extensión del agente Spring AI

Esta guía describe la implementación real de UniRide. Los primeros ejemplos de laboratorio usaban venta de entradas y Gemini; se conserva ese antecedente conceptual, pero sus snippets de `.entity(...)` y confirmación por texto no son aplicables a esta integración Groq. Los principios útiles siguen siendo descubrir beans con Spring, describir tools con precisión y reutilizar los servicios de negocio.

## Configuración actual

El [laboratorio original](historico/instrucciones_agente_spring_ai_laboratorio.md) se conserva como referencia histórica; sus snippets no son instrucciones de despliegue actuales.

- Java 21, Spring Boot 4.1.1, Spring AI 2.0.2.
- Starter `spring-ai-starter-model-openai`, conectado a `https://api.groq.com/openai/v1`.
- Clave privada `GROQ_API_KEY`; modelo `openai/gpt-oss-120b`, temperatura 0.1.
- `spring.ai.openai.max-retries: 0`, `spring.ai.retry.max-attempts: 1`, timeout 30 segundos y herramientas paralelas desactivadas.
- No habilitar Structured Outputs, JSON mode ni `.entity(ChatResponse.class)` en el flujo con tools. La [compatibilidad documentada por Groq](https://console.groq.com/docs/structured-outputs) excluye tool use con Structured Outputs.
- La aplicación utiliza Flyway y `ddl-auto: validate`; no cambiar el esquema con Hibernate.

## Responsabilidades

| Componente | Responsabilidad |
|---|---|
| AgentController | Validar petición de chat y devolver DTO |
| AgentService | Resolver usuario, sesión y contexto; llamar una vez al flujo del proveedor; mapear fallos |
| AgentConversationStore | Propiedad, TTL, límites y exclusión de mensajes simultáneos |
| AgentConfig | Prompt y construcción de ChatClient; no contiene permisos efectivos |
| AgentToolRegistry | Callbacks, límite por turno, recibos y construcción de resultados estructurados |
| AgentTools | Interpretación de argumentos, consultas autorizadas o preparación de una operación |
| AgentQueryService | Lecturas paginadas sin sincronizaciones ni efectos secundarios |
| ReportesAgentTools | Consultas personales de solo lectura y validación del rol activo para el ranking |
| EstadisticasService | Totales de actividad, frecuencia histórica, ranking de rutas y generación del historial en PDF |
| HistorialController | Descarga autenticada del PDF mediante `GET /api/historial/pdf` |
| MetodoCompensacionAgentTools | Consulta de métodos propios y preparación de registro, edición, cambio de estado o eliminación |
| ConfirmationService | Guardar propuesta, validar propietario/estado/expiración y ejecutar negocio una sola vez |
| ConfirmationController | Confirmación explícita recibida fuera del modelo |
| Services existentes | Autoridad de reglas de capacidad, fechas, estados, propiedad y notificaciones |

Spring descubre los componentes por `@ComponentScan`; usar inyección por constructor. Evitar dependencias circulares y llamadas manuales a configuración.

## Agregar una herramienta de consulta

1. Verificar la regla de negocio y autorización del endpoint equivalente. No basta con copiar `@PreAuthorize` al prompt.
2. Obtener identidad mediante `UsuarioService.usuarioActual()` y verificar el rol actual. No aceptar usuarioId/correo del modelo como propietario.
3. Reutilizar un servicio de consulta de solo lectura. Comprobar su implementación: un método llamado «listar» puede sincronizar y escribir, como ocurría con las rutas.
4. Usar paginación estable en SQL para listados. `PaginaAgente<T>` informa `hayMas`, `total`, `pagina` y `tamano`. Para agregaciones de negocio, conservar el contrato del servicio reutilizado.
5. Crear un DTO pequeño con datos estrictamente autorizados. No devolver entidades JPA, contraseñas, correos de terceros, teléfonos privados ni tokens.
6. Exponer un método `@Tool` con nombre estable, descripción clara y parámetros opcionales donde se pueda pedir un dato faltante. Validar argumentos en Java.
7. Registrar el bean en `AgentToolRegistry`. El sobre `ResultadoAgente(tipo,datos)` admite nuevos tipos; solo agregar una proyección a las listas antiguas si el tipo realmente corresponde a viajes/rutas/métodos.
8. Agregar pruebas de rol, propiedad, filtros, página siguiente y ausencia de escrituras. Simular el proveedor; nunca usar una clave real en tests.

`resultados` es el contrato extensible de `ChatResponse`. Las listas heredadas siguen disponibles para los clientes existentes.

### Herramientas de reportes implementadas

| Herramienta | Servicio y DTO | Selección desde el chat |
|---|---|---|
| `consultarMisEstadisticas` | `resumen()` → `EstadisticasRespuesta` | Totales completados por rol, cancelaciones o penalidades acumuladas |
| `consultarMiFrecuenciaViajes` | `analisisSemanal()` → `AnalisisSemanalRespuesta` | Frecuencia histórica, días más frecuentes, viajes de un día concreto y cancelaciones propias o de terceros |
| `consultarRankingMisRutas` | `rankingRutas()` → `RankingRutasRespuesta` | Rutas más usadas o penalidades por ruta del conductor autenticado |
| `prepararDescargaHistorialPdf` | Descriptor para `GET /api/historial/pdf`, que invoca `exportarHistorialPDF()` | Descarga del historial en PDF con filtros de fechas y tipo de viaje |

Los métodos de `EstadisticasService` obtienen la identidad a partir de `UsuarioService.usuarioActual()`. Las herramientas consumen el mismo presupuesto por turno que las demás consultas. El ranking requiere el rol activo `CONDUCTOR`, validado en el callback antes de llamar al servicio. Resumen, frecuencia y descarga PDF están disponibles para `CONDUCTOR` y `PASAJERO`.

La frecuencia se habilita a partir de cinco viajes completados y conserva los siete días, los recuentos de cancelaciones y los días con cero viajes. El ranking requiere cinco viajes completados como conductor. Si cualquiera de los dos devuelve `suficientesDatos: false`, se conserva el mensaje de negocio. El prompt orienta la selección de la herramienta y describe el alcance histórico sin filtros temporales.

Groq puede devolver texto válido con `STOP` y omitir uno o varios reportes. Después de validar esa respuesta, `AgentService` identifica peticiones explícitas de reportes en el mensaje actual. Los cuatro callbacks de la tabla pueden completar resultados faltantes. El servidor selecciona el nombre desde ese conjunto y usa `{}` como argumento. La ejecución pasa por `AgentToolRegistry` y comparte el presupuesto por turno, los recibos, la verificación de permisos y la captura en `resultados`. Si se agota el presupuesto, se aplica el manejo habitual de `TOOL_LIMIT`. El backend utiliza los datos capturados para componer `message` sin una segunda petición al proveedor.

El esquema de `prepararDescargaHistorialPdf` está vacío para el modelo. Java resuelve `desde`, `hasta` y `tipo` a partir del mensaje actual y pasa los filtros en un `ToolContext` interno. Si la petición de PDF es ambigua o inválida, el chat pide aclaración y conserva los demás resultados válidos del turno. El descriptor solo contiene metadatos y una ruta relativa. El endpoint autenticado aplica los filtros, consulta el historial del usuario y devuelve el PDF al cliente.

La detección de reportes se limita a expresiones explícitas en español. Las referencias de reportes que solo se entienden por la memoria de conversación, los listados y las búsquedas siguen el flujo habitual. El manejo de errores del proveedor y el protocolo de confirmación siguen aplicándose en sus respectivas rutas. El contrato HTTP y los ejemplos de Swagger están en [Reportes de viajes por chat](agente_flujo.md#reportes-de-viajes-por-chat).

## Agregar una herramienta de escritura

Una tool de escritura debe **preparar**, nunca ejecutar el servicio de negocio directamente.

1. Incorporar un tipo concreto en `TipoOperacion` y su DTO real de parámetros. Evitar deserialización polimórfica a clases indicadas por el modelo.
2. Extraer o reutilizar validadores puros del servicio existente; aplicarlos al preparar y al ejecutar. Jakarta Validation debe invocarse explícitamente para llamadas Java internas.
3. Resolver el recurso por filtros completos y detectar ambigüedad global antes de preparar. No seleccionar «el primero» entre varias coincidencias.
4. Guardar únicamente parámetros necesarios, ID del usuario autenticado, resumen exacto y snapshot de los datos que acepta el usuario. No almacenar el mensaje completo ni credenciales.
5. Reutilizar expiración, UUID aleatorio, bloqueo de confirmación propia y comprobación del rol actual. En ejecución, tomar locks compatibles con las operaciones tradicionales y comparar nuevamente el snapshot.
6. Ejecutar el servicio existente y guardar el resultado en la misma transacción. No marcar EJECUTADA si falla negocio o commit. No introducir reintentos de escrituras.
7. Registrar el nombre de la preparación en el límite de una operación por turno de `AgentToolRegistry`. Nunca registrar `confirmar` como `@Tool`.
8. Añadir tests de preparación sin efectos, rol cambiado, propiedad ajena, expiración, cancelación, snapshot cambiado, reglas revalidadas, rollback, confirmación duplicada y concurrencia PostgreSQL.

La aplicación confirma por `POST /api/agent/confirmaciones/{id}/confirmar`, sin parámetros ni cuerpo. El modelo no puede fabricar una aprobación. Repetir el mismo UUID ejecutado devuelve su recibo; si el resultado de red es incierto, consultar primero ese UUID. `GET` y `/cancelar` completan el protocolo. Ver [contratos completos](agente_flujo.md).

### Gestión de métodos de compensación

`MetodoCompensacionAgentTools` expone `actualizarMetodoCompensacion`, `cambiarEstadoMetodoCompensacion` y `eliminarMetodoCompensacion`. El modelo describe el método mediante `tipoActual` y `descripcionActual`. `actualizarMetodoCompensacion` admite `nuevoTipo` y `nuevaDescripcion`, y `cambiarEstadoMetodoCompensacion` utiliza `activo` para indicar el estado deseado. `ConfirmationService` consulta las coincidencias propias mediante `MetodoCompensacionService` y detecta ambigüedad antes de preparar el cambio.

`ConfirmationService` guarda el estado del método al preparar la operación y compara ese estado al confirmar. Los tres tipos nuevos reutilizan `MetodoCompensacionService` para aplicar los cambios, validar duplicados y conservar las solicitudes vinculadas. Flyway V8 amplía la restricción `CHECK` del tipo en `agent_confirmaciones`. El protocolo conserva el límite de una preparación por turno y utiliza los mismos endpoints autenticados de confirmación.

La detección de compensación evalúa el mensaje actual. Las preguntas reconocidas sobre tipos o procedimientos reciben una respuesta Java antes de llamar a Groq, con `operacion: null` y `resultados: []`. Las órdenes negadas bloquean la preparación. El registro exige que la herramienta de compensación corresponda a la intención actual y usa los argumentos validados a partir de la petición, incluso cuando Groq aporta otros valores.

Con una solicitud explícita y completa de registrar, editar, cambiar el estado o eliminar, el turno puede invocar el callback de preparación si Groq lo omitió y devolvió una respuesta válida con `STOP`. La llamada original y la recuperación comparten el presupuesto del turno, la captura de resultados y el límite de una preparación. La ejecución de negocio, la notificación y el recibo se guardan en una transacción al confirmar por HTTP.

La sesión mantiene borradores tipados para registrar, editar o eliminar. Un borrador conserva los datos explícitos de la petición y admite una respuesta breve al dato solicitado. Un cambio de tema lo descarta. La sesión conserva por separado el UUID y la expiración de una preparación reciente. Una afirmación aislada como «sí» recibe orientación antes de llamar a Groq, con `operacion: null` y `resultados: []`. Si el usuario envió otra petición, la afirmación recibe orientación general. El estado efectivo de cualquier UUID emitido puede consultarse en el endpoint GET de confirmaciones. Para las pruebas manuales, consulta [Gestión de métodos de compensación en Swagger](agente_flujo.md#probar-la-gestión-de-métodos-de-compensación).

## Salida y errores

La salida del LLM es texto no confiable. El servidor valida que conserve contenido y que respete el formato esperado. Las listas, operaciones, confirmaciones, cifras, descriptores de descarga e IDs proceden exclusivamente de callbacks Java y persistencia. El mensaje de una operación usa el resumen o resultado del backend. En los reportes, Java compone `message` a partir de las respuestas registradas en `resultados`, incluso si un turno combina estadísticas y una descarga PDF.

Un fallo antes de resultados se traduce a ErrorRespuesta con HTTP apropiado y código estable: 429 cuota, 502 formato/herramienta rechazada, 503 indisponibilidad/credencial del proveedor y 504 timeout. No exponer cuerpos de error Groq, prompts ni secretos en mensajes o logs. Si existen recibos, se devuelven con `advertencia` sin repetir llamadas. Ninguna solución debe crear una herramienta ficticia `json` ni desactivar la validación.

## Memoria

La sesión se identifica con UUID vinculado al usuario, no por correo. `conversationId` es opcional en la petición y vuelve en la respuesta. Omitirlo inicia sesión independiente. Se guardan veinte mensajes en RAM, con TTL de treinta minutos, máximo cien peticiones por sesión, cinco sesiones por usuario y mil por instancia. No se almacenan tool transcripts completos. Reiniciar pierde memoria; las confirmaciones persisten en PostgreSQL. El cambio de rol invalida el contexto. Los patrones de credenciales reconocidos se rechazan antes de enviar al proveedor; la detección es una protección adicional y no un sistema general de clasificación de datos personales.

## Pruebas y colaboración del equipo

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd verify
powershell -File scripts/test-agent-postgres.ps1
```

La suite rápida utiliza H2 aislado, mocks y un servidor HTTP loopback que reproduce la API Groq. La suite PostgreSQL utiliza Flyway real, Hibernate validate y pruebas de concurrencia/rollback. El script jamás acepta una URL de base existente. Las pruebas no deben consumir Groq ni Supabase.

Cada integrante debe añadir sus futuras tools sobre estos contratos, con DTO propios, consultas acotadas y el protocolo de confirmación compartido. Coordinar nombres de tipos, migraciones consecutivas y orden de locks; no alterar endpoints tradicionales ni implementar una memoria persistente compleja sin una necesidad concreta.
