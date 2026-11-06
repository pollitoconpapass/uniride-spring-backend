# Validación manual de US14

> Evidencia histórica de esta entrega. Para contratos y ejecución actuales prevalecen
> [endpoints](endpoints.md) y [operación/Flyway](operacion-hardening.md).
> El hardening añade plazos de 12 h, correo con reintentos y validación del esquema;
> los estados pasados de una prueba requieren fixtures, no crear viajes pasados por HTTP.

1. Iniciar sesión como `CONDUCTOR` (rol activo) y publicar dos rutas, con viajes programados. Como `PASAJERO`, enviar dos solicitudes a un viaje de la primera ruta y dejar la segunda ruta sin interesados.
2. Con el token del conductor, llamar `GET /api/solicitudes/recibidas/por-ruta`. Verificar que se incluyan **ambas** rutas y que la primera agrupe las dos solicitudes. La segunda debe incluir `solicitudes: []` y una recomendación para revisar la ruta.
3. Aplicar `?estado=PENDIENTE&page=0&size=10` y comprobar que el filtro no muestre solicitudes con otro estado. La paginación es por rutas.
4. Iniciar sesión como otro `CONDUCTOR` y comprobar que no puede ver las rutas ni solicitudes del primero.
5. Consultar `GET /api/solicitudes/recibidas/{solicitudId}/detalle` como dueño y verificar el nombre, carrera, distrito, mensaje de solicitud y demás datos de perfil no sensibles.
6. Intentar abrir el detalle con un conductor distinto: la respuesta debe ser `404`. Como `PASAJERO`, la autorización por rol debe denegar la consulta (`403`).
7. Con un pasajero sin perfil personal completo, comprobar que el detalle sigue dando `200`, con campos opcionales nulos y mensaje orientativo.
8. Probar `?page=-1`, `?size=0` y `?size=101`: deben devolver `400`.
9. Ejecutar `GET /api/viajes/{viajeId}/solicitudes` para comprobar que US14 conserva el endpoint anterior; ejecutar `GET /api/solicitudes/historial` como pasajero para descartar regresiones de US13.

No ejecutar migraciones SQL: US14 reutiliza las tablas existentes.
