# US10 — Guía de validación manual y endpoints

> Evidencia histórica de esta entrega. Para contratos y ejecución actuales prevalecen
> [endpoints](endpoints.md) y [operación/Flyway](operacion-hardening.md).
> El hardening añade plazos de 12 h, correo con reintentos y validación del esquema;
> los estados pasados de una prueba requieren fixtures, no crear viajes pasados por HTTP.

## Base de trabajo

- Rama base: `development` (PR #41 integrado, SHA `a4d5476`).
- Endpoint existente: `GET /api/viajes/buscar` — **mantiene** `Page<BusquedaRespuesta>`.
- Endpoint nuevo: `GET /api/viajes/buscar/resumen` — devuelve `{ viajes: Page<BusquedaRespuesta>, mensaje, sugerencia }`.
- Ambos aceptan los mismos filtros: `origen`, `destino`, `dia`, `hora`, `cercania`, `page`, `size`.
- `origen` y `destino` ahora aceptan coincidencias parciales (sin diferenciar mayúsculas), con resultados exactos primero.
- La cercanía **no usa GPS**: comprueba texto del distrito del perfil en el origen. Aún no son kilómetros reales.
- Un viaje disponible debe tener `PROGRAMADO`, salida futura, ruta no `ELIMINADA`, y al menos un cupo.
- `contribucionSugerida` se añade a cada resultado, con `aporteEstimado`, `litrosGasolinaAprox` y `sugerenciaFavor`; se emplea la misma fórmula que el endpoint `GET /api/viajes/{id}/sugerencia-contribucion`.
- Si aún no hay pasajeros aceptados, la referencia supone **1 pasajero** y lo indica expresamente.
- Los filtros de hora y día siguen siendo exactos; si no aparecen coincidencias, prueba quitarlos.

## Pruebas con JWT de PASAJERO

1. Crear 2 rutas y viajes futuros con origen `San Borja` y `San Borja Sur`, ambos destino `UPC`. El exacto debe listarse antes del parcial incluso si sale después.
2. `GET /api/viajes/buscar?origen=san%20borja&destino=upc` => `200`, priorización exacto > parcial.
3. `GET /api/viajes/buscar?origen=san%20borja&page=0&size=1` => `200`, la primera página debe tener el viaje más relevante.
4. `GET /api/viajes/buscar/resumen?origen=origen-imposible` => `200`, `viajes.content=[]`, `mensaje` y `sugerencia` no nulos.
5. Repetir con `GET /api/viajes/buscar?...` => `200`, mantiene `Page`, agrega cabecera `X-UniRide-Mensaje` para vacíos.
6. `GET /api/viajes/buscar?cercania=true` => sólo coincidencias con texto de `distrito` del perfil en el origen. Si falta perfil: `404`. Si distrito vacío: `400`.
7. Comprobar exclusión de viajes completos (`pasajeros == capacidadMaxima`), viajes cancelados, rutas eliminadas y salidas pasadas.
8. Comprobar en resultados `contribucionSugerida.aporteEstimado`, `.litrosGasolinaAprox`, `.sugerenciaFavor`; comparar monto con `GET /api/viajes/{id}/sugerencia-contribucion?pasajeros=1` cuando no haya pasajeros aceptados.
9. `GET /api/viajes/buscar?page=-1` o `size=101` => `400`.
10. Probar los filtros existentes `dia=Lunes`, `hora=08:00`, `cercania=false` y el orden temporal de desempate.

## Comprobación en PostgreSQL (solo lectura)

```sql
SELECT v.id, r.origen, r.destino, r.estado AS estado_ruta,
       v.fecha, v.hora, v.estado AS estado_viaje,
       v.pasajeros, r.capacidad_maxima
FROM viajes v JOIN rutas r ON v.ruta_id = r.id
ORDER BY v.fecha, v.hora;
```

## Sobre la documentación

El archivo original `docs/endpoints.md` de `development` se conserva, para evitar sobreescribir información de US09 integrada. Esta guía es una adenda con el nuevo contrato US10 y deberá consolidarse en `docs/endpoints.md` antes del cierre de la documentación del backend.

## Evidencia

Registrar capturas del JSON y códigos HTTP `200`, `400` y `404`, y resultados de `mvnw test`. No ejecutar PR sin `BUILD SUCCESS` en tests específicos y la suite completa.
