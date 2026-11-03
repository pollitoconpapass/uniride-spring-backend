# US09 — Validación manual de estados y listado de rutas

## Preparación

1. Actualiza la rama de desarrollo y aplica el kit US09. No requiere SQL nuevo.
2. En PowerShell ejecuta ` .\mvnw.cmd "-Dtest=RutaEstadoUs09ServiceTest,MisRutasUs09ControllerHttpTest" test` (sin espacio inicial).
3. Ejecuta ` .\mvnw.cmd test` para validar regresión (sin espacio inicial).
4. Inicia Spring Boot y autentícate en Swagger como `CONDUCTOR`.

## Escenarios

- **Sin rutas:** GET `/api/rutas` debe dar HTTP 200 con `content: []` y cabecera `X-UniRide-Mensaje`. GET `/api/rutas/resumen` devuelve JSON `mensaje` orientativo.
- **Primera ruta sin viajes:** POST `/api/rutas`, GET `/api/rutas/resumen`: estado `ACTIVA`.
- **Con viaje futuro y cupos:** POST `/api/rutas/{rutaId}/viajes`, GET resumen: `ACTIVA`.
- **Ruta LLENA:** crear un viaje futuro, aceptar tantas solicitudes como `capacidadMaxima` de esa ruta; GET `/api/rutas/resumen?estado=LLENA`: la ruta debe aparecer.
- **Ruta con viaje lleno y otro disponible:** crear un segundo viaje PROGRAMADO con cupos; GET `?estado=ACTIVA`: debe aparecer.
- **VENCIDA:** usar datos de prueba con viajes anteriores y sin ningún viaje PROGRAMADO futuro; GET `?estado=VENCIDA`: debe aparecer.
- **ELIMINADA:** eliminar con el flujo US08; el listado general la oculta, pero GET `?estado=ELIMINADA` la muestra y su estado no se modifica.
- **Filtro sin resultados:** GET `/api/rutas/resumen?estado=LLENA` cuando no existan rutas llenas debe devolver `rutas.content: []` y un `mensaje` que mencione `LLENA`.
- **Paginación:** GET `?page=0&size=5`; verificar `totalElements`, `totalPages` y `content`.

## Verificación PostgreSQL

```sql
SELECT id, origen, estado, capacidad_maxima
FROM rutas
ORDER BY id DESC;
```

Los estados se sincronizan al consultar `GET /api/rutas` o `/api/rutas/resumen` como conductor (no es un proceso programado). Si otro usuario modifica cupos, el estado persistido puede quedar temporalmente desactualizado hasta la siguiente consulta del conductor. Este límite se atenderá en la revisión transversal.

**Importante:** no se recalculan ni modifican `Viaje.estado`, solicitudes, reservas, penalidades ni rutas ELIMINADA.
