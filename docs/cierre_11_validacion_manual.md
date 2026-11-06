# Validación del kit único de cierre del punto 11

> Evidencia histórica de esta entrega. Para contratos y ejecución actuales prevalecen
> [endpoints](endpoints.md) y [operación/Flyway](operacion-hardening.md).
> El hardening añade plazos de 12 h, correo con reintentos y validación del esquema;
> los estados pasados de una prueba requieren fixtures, no crear viajes pasados por HTTP.

## Aplicación

1. `git status` (debe estar limpio); `git switch development`; `git pull origin development`.
2. `git switch -c fix/cierre-11-auditoria-integral`.
3. Extraer el ZIP en una carpeta separada; copiar únicamente `src` y `docs` a la raíz del repositorio. No copiar `CAMBIOS_CIERRE_11_4.diff` encima de archivos fuente.
4. `git diff --check`.
5. `./mvnw.cmd "-Dtest=CierreTotal114AutenticacionTest,CierreTotal114SeguridadTest,CierreTotal114PrivacidadPerfilTest,CierreTotal114MetodoCompensacionTest,CierreTotal114ErroresTest,CierreTotal114AccesoYMetodosRepositoryTest,PerfilServiceTest" test` en PowerShell.
6. `./mvnw.cmd test` suite completa.

## Swagger / Postman / navegador

**Autenticación:** en `POST /api/auth/login` enviar una contraseña incorrecta para una cuenta registrada. Se espera `401`; consultar `notificaciones` para verificar aviso `ERROR` y comprobar envío real si SMTP existe. Un correo inexistente devuelve `401` sin filtrar su existencia.

**Registro duplicado:** `POST /api/auth/registro` con correo ya existente: `409`, aviso independiente. **Verificación repetida**: `POST /api/auth/verificar` para cuenta verificada: `409`, aviso `ADVERTENCIA` independiente.

**CORS:** iniciar Angular en `http://localhost:4200` y hacer un `OPTIONS` a `http://localhost:8080/api/auth/login` con `Origin: http://localhost:4200` y `Access-Control-Request-Method: POST`; debe existir `Access-Control-Allow-Origin`. No usar `*` en producción. Para agregar dominios, configurar `CORS_ALLOWED_ORIGINS=http://localhost:4200,https://tu-frontend.example` antes de arrancar Spring Boot.

**Privacidad:** iniciar sesión como conductor A. GET `/api/perfil/usuario/{id_pasajero_que_envio_solicitud_a_A}` debe acceder al perfil; un usuario sin solicitud en rutas de A debe dar 404. Con conductor B, la solicitud de A no autoriza acceso.

**Métodos:** crear método, usarlo en una solicitud aunque posteriormente se cancele, intentar `DELETE /api/metodos-compensacion/{id}` → `409`; `PUT /api/metodos-compensacion/{id}` con `activo:false` sigue siendo la alternativa.

**SQL solo para desarrollo:**

```sql
SELECT id, usuario_id, tipo, mensaje, fecha_envio
FROM notificaciones
ORDER BY fecha_envio DESC LIMIT 15;
```

## Casos de regresión acumulados

- 11.1: eliminación física de viaje solo sin relaciones.
- 11.2: dos aceptaciones al último cupo admiten una sola.
- 11.3: cancelación, confirmación e inicio concurrentes no generan estados incompatibles.
- US13: historial y penalidades no exponen otras cuentas.
- US14: detalles de solicitudes solo del conductor propietario.
- US25: correo después del commit y errores de repetición persistentes.

**Atención:** los tests de integración usan H2; las comprobaciones de SMTP, navegador, concurrencia en PostgreSQL real y despliegue no quedan acreditadas solo por `BUILD SUCCESS`.
