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

Variables requeridas:

- `POSTGRES_PASSWORD`
- `JWT_SECRET`

Las variables de correo son opcionales para el entorno local.

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