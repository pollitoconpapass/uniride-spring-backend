# UniRide

Backend del proyecto hecho en Java con el framework Spring.

## Objetivo

Nuestro objetivo es organizar viajes compartidos entre estudiantes con rutas y horarios compatibles. A través de la misma tanto estudiantes universitarios que cuentan con automóvil y los que no, podrán acceder a todas las funcionalidades desde cualquier lugar y momento.

## Documentación

Toda la documentación del proyecto se encuentra dentro de la carpeta `docs`. Entre ellos están:

- Lista de todos los endpoints presentes hasta el momento: `endpoints.md`
- Diagramas relacionados con el código del proyecto: `diagramas.md`

Hardening final: [trazabilidad de 29 historias y 49 mappings](docs/historias-endpoints.md),
[operación, migraciones y seguridad](docs/operacion-hardening.md) e
[informe de auditoría y pruebas](docs/auditoria-hardening.md).
Se requiere Java 21. Ejecutar `mvnw.cmd clean test` y `mvnw.cmd verify` en Windows,
o `./mvnw clean test` y `./mvnw verify` en Linux. PostgreSQL adicional:
`mvnw.cmd -Ppostgres-it test` con una base temporal configurada según la guía.
El backend aplica Flyway y valida el esquema; las bases existentes necesitan
backup, revisión de colisiones y baseline explícito antes del primer arranque.

## Ejecución con Docker

### Requisitos

- Docker
- Docker Compose

### Configuración

Crear un archivo `.env` en la raíz tomando como referencia `.env.example`.

En PowerShell:

```powershell
Copy-Item .env.example .env
```

Configurar las siguientes variables:

- `POSTGRES_PASSWORD`: contraseña del usuario PostgreSQL utilizado en el entorno local.
- `JWT_SECRET`: clave Base64 utilizada para firmar los tokens JWT.
- `EMAIL_ADDRESS`: opcional para el entorno local.
- `EMAIL_PASSWORD`: opcional para el entorno local.
- `GROQ_API_KEY`: clave de Groq para el agente (`openai/gpt-oss-120b`).

Para generar una clave JWT segura desde PowerShell:

```powershell
$bytes = New-Object byte[] 64
[System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToBase64String($bytes)
```

El valor generado debe almacenarse únicamente en `.env`.

El archivo `.env` está excluido del repositorio mediante `.gitignore`
y no debe contenerse en commits ni Pull Requests.


### Ejecución

Construir y levantar los servicios:

```bash
docker compose up --build -d
```

### Verificar el estado:

```bash
docker compose ps
```
El backend queda disponible en:
http://localhost:8080/api
PostgreSQL se expone localmente mediante el puerto 5433.

### Detener los servicios:

```bash
docker compose down
```

---
## Documentación OpenAPI / Swagger

Con el backend en ejecución, la documentación interactiva de la API
está disponible en:

`http://localhost:8080/api/swagger-ui.html`

La especificación OpenAPI en formato JSON está disponible en:

`http://localhost:8080/api/v3/api-docs`

Los endpoints protegidos utilizan autenticación Bearer mediante JWT.
El token puede configurarse desde el botón `Authorize` de Swagger UI.

## Agente de IA

`POST /api/agent/chat` recibe `{"message":"Consulta mis rutas"}` con JWT.
La respuesta conserva los campos originales y agrega `conversationId`, `resultados`
y `advertencia`. Las decisiones de confirmación por texto devuelven también
`resolucionConfirmacion`. Para continuar una sesión, reenvía su `conversationId`.
Omitirlo inicia una conversación independiente. Las sesiones viven en RAM, expiran tras
30 minutos de inactividad y no se comparten entre usuarios.

El agente utiliza herramientas Java mediante Spring AI y la API compatible de
Groq. El backend construye los datos estructurados a partir de sus resultados;
no combina Structured Outputs con tool calling ni registra una herramienta `json`.
Las herramientas de escritura preparan propuestas con un resumen. Con el mismo
`conversationId`, el propietario puede responder «sí» o «confirmo» para ejecutar
la propuesta, o «no» o «cancelar» para descartarla. Los botones de la aplicación
pueden seguir usando los endpoints de confirmación y cancelación con JWT.
Ambas vías usan el mismo servicio transaccional, al que el modelo no tiene acceso.
El backend resuelve las consultas y preparaciones de compensación que reconoce
con datos suficientes, así como las sugerencias US29, sin una llamada al modelo
de chat. Si Groq devuelve un 429 después de que una herramienta entregó un
resultado, el chat conserva ese resultado y muestra una advertencia. [Validación en Swagger y Angular](docs/agente_flujo.md#verificar-la-gestión-de-cuota-en-swagger-y-angular)

El chat admite estadísticas personales y frecuencia histórica por día de la semana
para ambos roles. Los conductores también pueden consultar el ranking de sus rutas
con las penalidades asociadas. Los reportes se devuelven en `resultados`.
El chat también prepara la descarga del historial en PDF para ambos roles. El
archivo se descarga con el JWT del usuario mediante `GET /api/historial/pdf`.
Ambos roles pueden preparar el registro, la edición, la activación, la
desactivación y la eliminación de sus métodos de compensación desde el chat.
Los pasajeros pueden consultar una sugerencia de contribución (US29) en texto o
voz. Por ejemplo, envía `{"message":"¿Cuánto tendría que aportar por un viaje de Surco a la UPC?"}`
a `/api/agent/chat`. La respuesta informa el aporte estimado, su equivalente en
gasolina y una alternativa de favor acordado. La consulta es informativa y no
requiere confirmación. [Ejemplos de Swagger y resultado estructurado](docs/agente_flujo.md#sugerencia-de-contribución-us29)

Consulta los [contratos y ejemplos](docs/agente_flujo.md), la
[guía para agregar herramientas](docs/instrucciones_agente_spring_ai.md),
el [diseño del agente por voz](docs/agente_voz.md) y el
[informe de auditoría del agente](docs/auditoria-hardening-agente-ia.md).
No habilites `ddl-auto: update`: Flyway migra y Hibernate valida el esquema.
Las pruebas usan proveedores simulados y bases aisladas; no requieren una clave Groq real.

### Cambiar la contraseña de una base existente

Si el volumen de PostgreSQL ya fue inicializado, cambiar `POSTGRES_PASSWORD` en `.env` no modifica automáticamente la contraseña almacenada en la base.

Con PostgreSQL en ejecución, abrir:

```bash
docker compose exec postgres psql -U postgres -d uniride
```

Dentro de PostgreSQL, ejecutar:

```text
\password postgres
```

Introducir la nueva contraseña que se guardó en `.env` y salir:

```text
\q
```

Aplicar la configuración actualizada del backend:

```bash
docker compose up -d
```

---
## Internacionalización (i18n)

La API cuenta con soporte de internacionalización para mensajes de respuesta, validaciones y errores mediante la cabecera estándar HTTP `Accept-Language`.

### Idiomas soportados

| Código | Idioma | Estado |
|---|---|---|
| `es-419` | Español latinoamericano | **Predeterminado (Default)** |
| `en-US` | Inglés (Estados Unidos) | Soportado |

### Uso

Para recibir las respuestas en inglés estadounidense:
```http
Accept-Language: en-US
```

Para recibir las respuestas en español latinoamericano (o por defecto si se omite el header):
```http
Accept-Language: es-419
```
