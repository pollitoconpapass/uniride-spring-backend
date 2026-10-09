# UniRide

Backend del proyecto hecho en Java con el framework Spring.

## Objetivo

Nuestro objetivo es organizar viajes compartidos entre estudiantes con rutas y horarios compatibles. A través de la misma tanto estudiantes universitarios que cuentan con automóvil y los que no, podrán acceder a todas las funcionalidades desde cualquier lugar y momento.

## Documentación

Toda la documentación del proyecto se encuentra dentro de la carpeta `docs`. Entre ellos están:

- Lista de todos los endpoints presentes hasta el momento: `endpoints.md`
- Diagramas relacionados con el código del proyecto: `diagramas.md`

## Ejecución con Docker

### Requisitos

- Docker
- Docker Compose

### Configuración

Crear un archivo `.env` en la raíz tomando como referencia `.env.example`.

PoweShell:
Copy-Item .env.example .env

Variables requeridas:

- `POSTGRES_PASSWORD`
- `JWT_SECRET`

Las variables de correo son opcionales para el entorno local.





Después de crear `.env`, ejecutar este bloque en PowerShell desde la raíz del proyecto:

```powershell
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()

$dbBytes = New-Object byte[] 32
$jwtBytes = New-Object byte[] 64

$rng.GetBytes($dbBytes)
$rng.GetBytes($jwtBytes)
$rng.Dispose()

$contenido = Get-Content .env -Raw

$contenido = $contenido -replace '(?m)^POSTGRES_PASSWORD=.*$', (
    'POSTGRES_PASSWORD=' + [Convert]::ToBase64String($dbBytes)
)

$contenido = $contenido -replace '(?m)^JWT_SECRET=.*$', (
    'JWT_SECRET=' + [Convert]::ToBase64String($jwtBytes)
)

$rutaEnv = Join-Path (Get-Location).Path '.env'

[IO.File]::WriteAllText(
    $rutaEnv,
    $contenido,
    [Text.UTF8Encoding]::new($false)
)
```

Este comando genera una contraseña aleatoria y una clave JWT de 64 bytes en Base64. Guarda ambos valores directamente en `.env`, sin imprimirlos.







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
