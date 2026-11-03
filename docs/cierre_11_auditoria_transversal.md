# Cierre unificado del punto 11 — Auditoría transversal UniRide

**Rama fuente:** `development` de `pollitoconpapass/uniride-spring-backend`. **Commit verificado:** `e81c0c1b71930d1d37a30196deba6b95b1382ab2` (PR #48, 11.3).

## Hallazgos confirmados y correcciones del kit

| Riesgo confirmado en `development` | Impacto | Corrección | Evidencia de código |
|---|---|---|---|
| Notificación de inicio de sesión fallido/registro duplicado guardada en transacción que lanza excepción | Se deshace el aviso y nunca se envía correo | `REQUIRES_NEW` por medio de `notificarErrorAutenticacion` | `AuthService`, `NotificacionService` |
| Notificación de cuenta ya verificada seguida de `BusinessException` | Aviso revertido | `notificarAccionYaRealizada` | `AuthService.verificarCodigo` |
| `UsuarioDetailsService` construía `UserDetails` activo aun con `cuentaVerificada=false` | JWT existente podría autenticar cuenta sin verificar | `.disabled(...)` y comprobación en `JwtAuthFilter` | `UsuarioDetailsService`, `JwtAuthFilter` |
| No hay política CORS explícita | Angular en otro origen puede fallar por preflight | CORS para orígenes exactos configurables y cabecera `X-UniRide-Mensaje` expuesta | `SecurityConfig` |
| `/perfil/usuario/{id}` aceptaba cualquier ID de perfil con rol CONDUCTOR | Lectura de perfiles de personas sin relación con el conductor | Exigir relación de solicitud con ruta propia; ausencia y acceso ajeno responden 404 | `PerfilService`, `SolicitudRepository` |
| Eliminación de método de compensación solamente protegida para aceptada/confirmado | Posible FK/500 ante solicitudes pendientes, rechazadas o históricas | Bloquear eliminación ante **cualquier** solicitud vinculada; sugerir desactivación | `MetodoCompensacionService`, `MetodoCompensacionRepository` |
| El manejador 500 pasaba `e.getMessage()` a la respuesta JSON | Posible exposición de consultas y nombres internos | Log interno, mensaje genérico al cliente, `409` limpio ante integridad de datos | `GlobalExceptionHandler` |

**Alcance:** 7 grupos de correcciones en un solo PR, sin cambiar contratos de endpoints ni entidades/tablas. Se actualiza también `PerfilServiceTest` por la nueva dependencia y se suman 16 pruebas automatizadas. No se reemplazan archivos de los kits 11.1–11.3.

## Matriz mínima para cerrar el punto 11

- [ ] `mvnw test` completa en verde, incluyendo H2 e integración de concurrencia de 11.2 y 11.3.
- [ ] Registro con correo existente: `409`, aviso conservado en BD y correo si SMTP está configurado.
- [ ] Login con contraseña incorrecta de cuenta existente: `401`, aviso conservado; sin token.
- [ ] Verificación repetida: `409`, aviso conservado.
- [ ] Cuenta sin verificar con JWT preexistente: denegación en endpoint protegido.
- [ ] Preflight desde `http://localhost:4200` aceptado; origen no permitido sin cabecera CORS habilitante.
- [ ] Endpoint `/api/perfil/usuario/{id}`: `200` para pasajero con solicitud del conductor, `404` para un extraño.
- [ ] Eliminar método de compensación utilizado por cualquier solicitud: `409` y método conservado; desactivar permitido.
- [ ] Fallos imprevistos: respuesta `500` genérica sin SQL ni detalles internos; conflicto de integridad: `409`.
- [ ] Regresión: cupos de 11.2, transiciones de 11.3, eliminación 11.1, US25, US13 y US14.
- [ ] Revisión de GitHub Actions: si no existen workflows, la prueba local es obligatoria y debe adjuntarse evidencia al PR.

## Riesgos de producción que no deben declararse resueltos por este kit

1. **SMTP sin cola ni reintentos:** los correos se intentan después del commit; un fallo SMTP no tiene reintento. Para una implantación real, considerar un patrón outbox/cola y una política de reintentos.
2. **Rate limiting:** no se observa limitación por IP/usuario en login, registro, verificación y reenvío de código. Recomendable implementar en el API gateway o en la aplicación antes de exposición pública.
3. **PostgreSQL:** la configuración actual utiliza `ddl-auto: update`; para producción conviene migraciones versionadas, `validate` y secretos externos.
4. **Concurrencia fuera de las pruebas existentes:** ejecutar pruebas sobre PostgreSQL real antes de producción, porque H2 no reproduce exactamente los bloqueos de PostgreSQL.
5. **Verificación real de correo:** si SMTP está sin configurar, el aviso queda en BD pero no llega al usuario. No prometer email entregado sin comprobarlo.
6. **Validación de flujo Angular y despliegue:** la configuración CORS local facilita el desarrollo, pero cada dominio desplegado debe estar permitido explícitamente mediante `CORS_ALLOWED_ORIGINS`.

La auditoría es una inspección estática del repositorio, no un certificado de que todas las historias de usuario estén funcionalmente aprobadas. El paso 12 validará la matriz de aceptación de 29 US y el paso 14 la integración con Angular.
