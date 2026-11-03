# UniRide - Validación US25: notificaciones por correo

## Alcance y condiciones previas

Basado en la rama `development` commit `22a6ae5` (PR #44, US14 integrado). Esta corrección **no cambia endpoints, DTO, entidades ni esquema PostgreSQL**.

US25 en el informe (escenarios):
1. **Exitoso:** al crear/publicar un viaje válido, recibir una notificación de éxito.
2. **Error:** repetir una acción ya completada, recibir una notificación que informe que esa acción estaba registrada.
3. **Alternativo:** un viaje PROGRAMADO sin confirmar recibe **un solo recordatorio** cuando faltan de 12 a 20 horas para la salida.

Se almacenan avisos en `notificaciones`, además del intento de envío por correo SMTP. Para comprobar recepción real, configurar correctamente `EMAIL_ADDRESS` y `EMAIL_PASSWORD` en el entorno y usar una cuenta de destinatario a la que se tenga acceso. La notificación persistida en DB no demuestra por sí sola recepción del correo.

## Pruebas automatizadas

Desde la raíz del repositorio, con Java 21:

```powershell
.\mvnw.cmd "-Dtest=NotificacionUs25ServiceTest,SolicitudNotificacionUs25Test,ViajeNotificacionUs25Test" test
.\mvnw.cmd test
```

**No subir ni fusionar el PR si alguna falla.**

## Validación en Swagger (http://localhost:8080/api/swagger-ui.html)

1. Inicia sesión con una cuenta de rol CONDUCTOR con perfil completo y una ruta válida. Ejecuta `POST /api/rutas/{rutaId}/viajes` con fecha futura y hora. Esperado: `201 Created`, registro `EXITO` en `notificaciones` para el conductor, correo enviado si SMTP está correctamente configurado.
2. Ejecuta `PUT /api/viajes/{id}/confirmar` para un viaje programado con **al menos 12 horas** restantes. Esperado: `200`, confirmación y aviso. Repite exactamente la misma operación: debe responder `200` indicando que el viaje **ya estaba confirmado**, sin modificar el estado ni generar penalidad, y registrar el aviso correspondiente.
3. Como PASAJERO, envía una solicitud válida a un viaje diferente del propio (`POST /api/solicitudes/viajes/{viajeId}`). Repite la misma solicitud. Esperado: `409 Conflict`, mensaje `Ya enviaste una solicitud para este viaje`, **sin insertar otra solicitud**, y aviso `ADVERTENCIA` al pasajero con correo (si SMTP funciona).
4. Repite `PUT /api/solicitudes/{id}/cancelar` sobre una solicitud ya `CANCELADA`, o `PUT /api/solicitudes/{id}/aceptar` sobre una ya `ACEPTADA`, utilizando el rol autorizado. Esperado: `409 Conflict` sin cambios adicionales y notificación sobre la acción ya realizada. El mismo principio aplica a rechazos ya ejecutados.
5. Para el recordatorio, crea un viaje `PROGRAMADO` futuro que permanezca sin confirmar y cuyo inicio esté entre **12 y 20 horas** adelante. El scheduler `enviarRecordatoriosAutomaticos` se ejecuta periódicamente cada 5 minutos (`fixedRate = 300000`). Confirma que se registra **una única notificación `RECORDATORIO`**, aunque el scheduler vuelva a ejecutarse. La selección usa bloqueo pesimista en BD y la marca `recordatorio_enviado`.
6. Comprueba que una salida con más de 20 horas, con menos de 12 horas, ya confirmada, cancelada o con `recordatorio_enviado = true` no genere nuevos recordatorios.

### Consultas de comprobación en PostgreSQL

**Reemplaza** los IDs con valores reales. No ejecutes SQL de actualización para simular el resultado sin preparar primero datos de prueba.

```sql
SELECT id, usuario_id, tipo, mensaje, leida, fecha_envio
FROM notificaciones
WHERE usuario_id = 123
ORDER BY fecha_envio DESC;

SELECT id, ruta_id, fecha, hora, estado, confirmado, recordatorio_enviado
FROM viajes
WHERE id = 456;

SELECT id, pasajero_id, viaje_id, estado
FROM solicitudes
WHERE pasajero_id = 123 AND viaje_id = 456;
```

## Restricciones / pendientes de arquitectura

- El envío SMTP se intenta **después de confirmar la transacción**. Ante rollback, no se envía correo de éxito de una operación fallida.
- Si SMTP falla, la notificación queda guardada en `notificaciones` y el problema se registra en logs; **no hay reintentos automáticos ni garantía de entrega**. Se requiere una outbox/cola de correo y un estado de entrega para garantizar el reintento en una futura mejora.
- Los avisos de acciones duplicadas usan una transacción `REQUIRES_NEW` para no desaparecer cuando la operación original termina con HTTP 409.
- El scheduler conserva el mismo intervalo y ventana de US17. La marca de `recordatorio_enviado` garantiza que el aviso no se procese nuevamente una vez guardado.
- No se agregan endpoints para consultar notificaciones: US25 exige envío al correo y registro de la notificación; el listado por API queda para otra decisión de producto.

## Commit y PR

```powershell
git status
git diff --check
git add src docs/US25_validacion_manual.md
git commit -m "fix: asegurar notificaciones e idempotencia US25"
git push -u origin fix/us25-notificaciones-idempotencia
```

Crear PR con **base `development`**, **compare `fix/us25-notificaciones-idempotencia`**. Verificar la ejecución de pruebas completas y los pasos de Swagger antes del merge.
