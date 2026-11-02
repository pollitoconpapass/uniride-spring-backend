# Especialización de usuarios: Conductor y Pasajero (Paso 3)

## Decisión de diseño

- `usuarios`: identidad y autenticación comunes. `rol_principal` es el rol ACTIVO para las autorizaciones.
- `conductores`: PK `usuario_id` también FK a `usuarios(id)` y `fecha_alta`.
- `pasajeros`: PK `usuario_id` también FK a `usuarios(id)` y `fecha_alta`.
- Un mismo usuario puede tener dos perfiles; cambiar el rol activo NO elimina el anterior.
- Rutas y vehículos usan `conductor_id`; solicitudes usan `pasajero_id`. Las tres columnas conservan su nombre y los IDs existentes para NO romper endpoints, repositorios ni historial.
- La migración añade FKs adicionales a las tablas especializadas para comprobar que cada conductor/pasajero de un viaje posea su respectivo perfil. Las relaciones JPA existentes con `Usuario` permanecen por compatibilidad en este PR; no se duplican nombres, correo ni contraseña.

## Migración de base de datos

1. Respaldar la base `uniride` (pgAdmin > Backup). No correr sobre producción sin ensayo previo.
2. Actualizar la rama y ejecutar pruebas Java en H2 (`.\mvnw.cmd test`). Detener Spring Boot antes de modificar PostgreSQL.
3. Ejecutar SQL `db/migrations/2026-11-02_00_us08_estado_eliminada.sql` para sincronizar US08 en otros equipos si fuera necesario.
4. Ejecutar `db/migrations/2026-11-02_01_crear_perfiles_roles.sql` en pgAdmin Query Tool sobre la BD correcta. No es necesario iniciar Spring antes; el SQL crea tablas y FKs.
5. Iniciar Spring Boot y comprobar que se conecta a PostgreSQL sin errores. Verificar que existen filas para todos los IDs históricos y que las FKs se crearon.
6. Iniciar sesión y probar cambio de rol. Verificar que aparezca una fila en la tabla correspondiente sin duplicados.

**Estos scripts están versionados, pero NO se ejecutan automáticamente.** Flyway/Liquibase se adoptarán posteriormente cuando se estabilice el baseline del esquema; no asumir que `ddl-auto=update` actualiza restricciones `CHECK`/FK.

## Consultas de verificación

```sql
SELECT 'conductores' tipo, count(*) total FROM conductores
UNION ALL SELECT 'pasajeros', count(*) FROM pasajeros;

SELECT r.id FROM rutas r LEFT JOIN conductores c ON c.usuario_id = r.conductor_id
WHERE c.usuario_id IS NULL;

SELECT s.id FROM solicitudes s LEFT JOIN pasajeros p ON p.usuario_id = s.pasajero_id
WHERE p.usuario_id IS NULL;
```

Las dos últimas consultas deben devolver **0 filas**.

## Límites del cambio

Se conservan los DTO, endpoints, perfiles personales, contraseñas, tokens JWT y reglas US08/US17/US18/US24. La separación es de especializaciones y restricciones relacionales; no mueve los datos personales compartidos fuera de `usuarios`/`perfiles`.
