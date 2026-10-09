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

En PowerShell:

```powershell
Copy-Item .env.example .env
```

Configurar las siguientes variables:

- `POSTGRES_PASSWORD`: contraseña del usuario PostgreSQL utilizado en el entorno local.
- `JWT_SECRET`: clave Base64 utilizada para firmar los tokens JWT.
- `EMAIL_ADDRESS`: opcional para el entorno local.
- `EMAIL_PASSWORD`: opcional para el entorno local.

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
