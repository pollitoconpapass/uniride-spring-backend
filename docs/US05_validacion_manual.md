# US05 — Matriz de pruebas manuales

Endpoint: `PUT /api/perfil/info-adicional`. Autorizar Swagger con JWT. Antes de empezar, el usuario debe haber completado `PUT /api/perfil`.

1. Primera carga con tres campos válidos: `200`; comprobar `GET /api/perfil` y `notificaciones`.
2. Primera carga sin `hobbies`: `400` con mensaje que menciona `hobbies`; no guardar datos parciales.
3. Primera carga `{}`: `400` indicando `gustos`, `hobbies`, `datosCuriosos`.
4. Primera carga con `gustos` = espacios: `400`, sin cambios.
5. Con información completa, enviar solo `{"hobbies":"Ciclismo"}`: `200`; comprobar que `gustos` y `datosCuriosos` anteriores permanecen.
6. Con información completa, enviar `{"gustos":" "}`: `400`; los valores se conservan.
7. Con información completa, enviar `{}`: `400` solicitando al menos un campo.
8. Con usuario sin perfil personal, intentar guardar datos adicionales: `404` y mensaje "Primero completa tu perfil personal".
9. Sin JWT o con token inválido: acceso denegado por seguridad (verificar el código HTTP configurado).

Consulta de BD (cambiar el ID):

```sql
SELECT usuario_id, gustos, hobbies, datos_curiosos
FROM perfiles
WHERE usuario_id = 1;
```

**Sin migración SQL para US05**: columnas existentes. Los cambios del modelo Conductor/Pasajero siguen siendo un requisito de despliegue separado si aún no se ejecutaron las migraciones de aquel PR.
