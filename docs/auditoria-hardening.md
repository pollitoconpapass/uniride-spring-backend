# Auditoría backend hardening

## Punto de partida

- Rama: `fix/backend-hardening-final`; base local `origin/development`.
- Commit inicial: `21bb11dac83f74fb134776b973d6feb98d60911c`.
- Árbol inicial limpio. No modificar master/development, ni push/merge/PR.
- Baseline: `mvnw.cmd test`, 302 pruebas, 55 clases, 0 fallos/errores/omitidas.
  Ejecución inicial con Java 25.0.4.1; bytecode objetivo Java 21.
- Docker CLI disponible, daemon Linux detenido al comienzo.

## Hallazgos confirmados y prioridades

1. Alta: creación solo compara fecha; reprogramación sin confirmar permite salidas
   imposibles de confirmar; falta vencimiento automático e inicio prematuro.
2. Alta: edición de capacidad y eliminación de ruta no comparten bloqueo con
   aceptación/creación. El listado US09 puede sobrescribir una eliminación concurrente.
3. Alta: OTP en claro, sin límite de intentos ni reenvíos; autenticación pública
   permite multiplicar avisos de error y llamadas SMTP.
4. Media: estadísticas infieren autor de cancelación a partir de penalidades;
   PDF incluye PROGRAMADO futuro de hoy; perfil y usuario divergen.
5. Media: paginación inválida alcanza PageRequest sin validar en varios flujos;
   fechas académicas sin validación de orden; correo/teléfono no canónicos.
6. Media: SMTP pierde entregas fallidas; OCR no limita páginas/dimensiones;
   ddl-auto=update sin historial de migraciones, CI ausente.
7. Documentación: índice rápido incompleto; eliminación de métodos descrita con
   una restricción más débil que la implementación; falta el mapa completo de mappings.

## Criterios de compatibilidad

US17 conserva 12 horas, US18 conserva >24 sin penalidad, 4..24 LEVE y <4 GRAVE.
US20 documenta frecuencia por día de la semana (histórica), no últimos siete días.
No se atribuirán cancelaciones históricas sin autor registrado. Los cierres 11.1,
11.2, 11.3 y 11.4 se verifican mediante sus pruebas existentes y nuevos casos.

Este documento se completará con cambios, comandos, resultados y limitaciones
efectivamente verificados; el baseline por sí solo no demuestra cierre de la auditoría.

## Fase 1

Creación/reprogramación exigen salida futura y >=12 h. Inicio exige hora de salida.
Vencimiento automático serializado sobre Viaje, incluso salidas pasadas; un único
cambio de estado, penalidad y avisos. Historial y solicitudes conservados.
Autor/fecha de cancelación permiten reintentar al mismo pasajero sin nuevos efectos.
Ruta se bloquea antes de crear/editar/eliminar y sus viajes antes de validar cupos;
US09 bloquea rutas para no resucitar eliminaciones. Orden: rutas por id, viajes por id.
Clock inyectable en servicios/JWT; zona Lima explícita en callbacks de persistencia.

Validación: suite completa 315 pruebas, 56 clases, 0 fallos/errores/omitidas;
ejecución focal posterior de ViajeHardeningTest y ViajeServiceTest exit 0.
