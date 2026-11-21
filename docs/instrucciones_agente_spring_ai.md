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
| ConfirmationService | Guardar propuesta, validar propietario/estado/expiración y ejecutar negocio una sola vez |
| ConfirmationController | Confirmación explícita recibida fuera del modelo |
| Services existentes | Autoridad de reglas de capacidad, fechas, estados, propiedad y notificaciones |

Spring descubre los componentes por `@ComponentScan`; usar inyección por constructor. Evitar dependencias circulares y llamadas manuales a configuración.

## Agregar una herramienta de consulta

1. Verificar la regla de negocio y autorización del endpoint equivalente. No basta con copiar `@PreAuthorize` al prompt.
2. Obtener identidad mediante `UsuarioService.usuarioActual()` y verificar el rol actual. No aceptar usuarioId/correo del modelo como propietario.
3. Reutilizar un servicio de consulta de solo lectura. Comprobar su implementación: un método llamado «listar» puede sincronizar y escribir, como ocurría con las rutas.
4. Consultar con paginación estable en SQL. Usar `PaginaAgente<T>` e informar `hayMas`, `total`, `pagina` y `tamano`; evitar listas completas y límites ocultos.
5. Crear un DTO pequeño con datos estrictamente autorizados. No devolver entidades JPA, contraseñas, correos de terceros, teléfonos privados ni tokens.
6. Exponer un método `@Tool` con nombre estable, descripción clara y parámetros opcionales donde se pueda pedir un dato faltante. Validar argumentos en Java.
7. Registrar el bean en `AgentToolRegistry`. El sobre `ResultadoAgente(tipo,datos)` admite nuevos tipos; solo agregar una proyección a las listas antiguas si el tipo realmente corresponde a viajes/rutas/métodos.
8. Agregar pruebas de rol, propiedad, filtros, página siguiente y ausencia de escrituras. Simular el proveedor; nunca usar una clave real en tests.

No agregar campos vacíos de perfil, horarios o reportes a ChatResponse para reservar funcionalidades futuras. Las listas heredadas se mantienen por compatibilidad; el contrato extensible es `resultados`.

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

## Salida y errores

La salida del LLM es texto no confiable. El servidor valida que no esté vacía, truncada o en un formato inesperado. Las listas, operaciones, confirmaciones y sus IDs proceden exclusivamente de callbacks Java y persistencia. No reconstruirlos desde JSON del modelo. El mensaje de una operación se reemplaza por el resumen/resultado del backend.

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
