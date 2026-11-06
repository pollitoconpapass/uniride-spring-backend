# Especialización de usuarios: Conductor y Pasajero (Paso 3)

## Decisión de diseño

- `usuarios`: identidad y autenticación comunes. `rol_principal` es el rol ACTIVO para las autorizaciones.
- `conductores`: PK `usuario_id` también FK a `usuarios(id)` y `fecha_alta`.
- `pasajeros`: PK `usuario_id` también FK a `usuarios(id)` y `fecha_alta`.
- Un mismo usuario puede tener dos perfiles; cambiar el rol activo NO elimina el anterior.
- Rutas y vehículos usan `conductor_id`; solicitudes usan `pasajero_id`. Las tres columnas conservan su nombre y los IDs existentes para NO romper endpoints, repositorios ni historial.
- La migración añade FKs adicionales a las tablas especializadas para comprobar que cada conductor/pasajero de un viaje posea su respectivo perfil. Las relaciones JPA existentes con `Usuario` permanecen por compatibilidad en este PR; no se duplican nombres, correo ni contraseña.

## Migración de base de datos

Seguir [operación y migraciones Flyway](operacion-hardening.md#base-de-datos):
backup/restauración de una copia, revisión de colisiones y baseline explícito para
bases existentes. La aplicación usa `ddl-auto=validate` y aplica V1..V6.
Los scripts de `db/migrations/` son evidencia histórica y no se ejecutan además
de Flyway. V2 incorpora los perfiles y V6 comprueba las FK por tabla incluso si
otro esquema contiene restricciones con el mismo nombre.

Después del arranque, verificar filas y FK con las consultas siguientes e iniciar
sesión para probar cambio de rol sin duplicados.

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
