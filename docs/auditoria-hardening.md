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
US21 cuenta solo penalidades del conductor, según decisión explícita del responsable.
No se atribuirán cancelaciones históricas sin autor registrado. Los cierres 11.1,
11.2, 11.3 y 11.4 se verifican mediante sus pruebas existentes y nuevos casos.

El informe A–M siguiente registra los cambios y resultados verificados.
El baseline por sí solo no demuestra cierre de la auditoría.

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

## A. Rama, base y commit inicial

`fix/backend-hardening-final`, origin verificado contra el repositorio solicitado.
Base y merge-base con `origin/development`: `21bb11dac83f74fb134776b973d6feb98d60911c`.
No cambios iniciales del usuario. No se modificaron master/development.

## B. Problemas iniciales confirmados

Los siete grupos de prioridades anteriores fueron confirmados por código y pruebas.
Además de compilar se verificaron ownership/roles, estados, claves foráneas,
idempotencia, concurrencia, ventanas temporales, privacidad y cuerpos de respuesta.
La suite inicial carecía de EstadisticasServiceTest y vencimiento automático.

## C. Hallazgos adicionales

- Reenvío público de OTP devolvía teléfono, nombres, id y rol de cuentas pendientes.
- Avisos de autenticación permitían abuso de notificaciones/SMTP mediante llamadas públicas.
- US09 podía sobrescribir una eliminación concurrente con una entidad Ruta obsoleta.
- Aviso REQUIRES_NEW mientras Usuario estaba bloqueado podía esperar su propia FK;
  ahora se registra después de completar/liberar la transacción de verificación.
- Cola SMTP necesitaba descartar OTP reemplazados/vencidos y no compartir scheduler
  con vencimientos de viajes. La revisión independiente detectó ambos casos.
- Teléfonos históricos con prefijo `00` necesitaban la misma normalización que registro.
- TXT podía provocar backtracking cuadrático y una línea de horario sin nombre
  podía causar NPE. Se acotó el texto antes de regex y se corrigió el manejo de null.
- Multipart ausente, métodos/tipos HTTP inválidos y rutas inexistentes terminaban
  en 500 por el handler genérico; ahora preservan 400/405/415/404.
- Solicitudes rechazadas/canceladas previamente no deben contarse como afectadas
  por cancelación de terceros. Se persiste participación efectiva desde V5.
- Relación de acuerdo era ManyToOne frente a un inverso OneToOne: ahora es OneToOne,
  con unicidad en BD. Se agregan unicidad de solicitud activa e índices de consulta.
- Consultas de perfil por pasajero y de solicitudes por ruta producían N+1: se agrupan
  por los ids de la página y se cargan relaciones to-one con EntityGraph.
- La imagen Jammy traía Tesseract 4 frente a configuración para 5 y fallaba la carga
  nativa. La imagen final Noble usa Tesseract 5.3.4 mediante CLI dentro del worker
  limitado; el PDF escaneado se verificó por HTTP y se agregó la regresión a CI.
- V2 buscaba restricciones por nombre global: omitía FK de perfiles de rol si otro
  esquema tenía nombres iguales. V6 lo corrige por identidad de tabla sin cambiar V2.
  La prueba reprodujo 0/3 FK antes y verifica 3/3 validadas después.
- La consulta de dependencias identificó versiones con avisos en Jackson, Tomcat y
  Bouncy Castle; se aplicaron parches dentro de las mismas líneas de versión.

## D. Cambios y commits por fase

| Fase | Commit local | Cambio |
|---|---|---|
| 0 | `5d9db87` | Baseline y prioridades |
| 1 | `8e65bb2` | Plazos/estados, autor de cancelación, bloqueos de rutas/viajes, Clock |
| 2 | `53cbc6a` | Estadísticas, PDF, perfil y paginación |
| 3 | `1ba244c` | OTP, rate limiting, outbox, OCR, Flyway, CI, pruebas PostgreSQL |
| 4 | Commit local de cierre `test: cerrar regresion integral y documentar hardening` | Regresión ampliada, consultas JPA, V6 e inventario/documentación final |

No se eliminan cascadas protectoras ni se desactiva seguridad. No se eliminaron
clases de prueba. Dos stubs innecesarios se retiraron de una prueba de PDF ilegible
porque el rechazo ocurre ahora antes de consultar usuario/horario; se conserva
la misma aserción de error. Fixtures de OTP utilizan el nuevo lookup bloqueado.

## E. Archivos modificados

```text
.github/workflows/ci.yml
Dockerfile
README.md
db/migrations/2026-11-05_00_autor_cancelacion.sql
docker-compose.yml
docs/11_2_concurrencia_cupos.md
docs/11_3_regresion_estados_seguridad.md
docs/US05_validacion_manual.md
docs/US07_validacion_manual.md
docs/US09_validacion_manual.md
docs/US10_validacion_manual.md
docs/US13_endpoints.md
docs/US13_validacion_manual.md
docs/US14_endpoints.md
docs/US14_validacion_manual.md
docs/US25_validacion_manual.md
docs/auditoria-hardening.md
docs/cierre_11_auditoria_transversal.md
docs/cierre_11_validacion_manual.md
docs/diagramas.md
docs/endpoints.md
docs/historias-endpoints.md
docs/integridad_viajes_11_1.md
docs/modelo-roles.md
docs/operacion-hardening.md
pom.xml
src/main/java/com/uniride/config/TiempoConfig.java
src/main/java/com/uniride/controllers/HorarioAcademicoController.java
src/main/java/com/uniride/dto/requests/LoginRequest.java
src/main/java/com/uniride/dto/requests/ReenviarCodigoRequest.java
src/main/java/com/uniride/dto/requests/RegistroRequest.java
src/main/java/com/uniride/dto/requests/VerificarCodigoRequest.java
src/main/java/com/uniride/entities/AcuerdoCompensacion.java
src/main/java/com/uniride/entities/ArchivoCarga.java
src/main/java/com/uniride/entities/Conductor.java
src/main/java/com/uniride/entities/CorreoPendiente.java
src/main/java/com/uniride/entities/HorarioAcademico.java
src/main/java/com/uniride/entities/Notificacion.java
src/main/java/com/uniride/entities/Pasajero.java
src/main/java/com/uniride/entities/Penalidad.java
src/main/java/com/uniride/entities/Perfil.java
src/main/java/com/uniride/entities/Ruta.java
src/main/java/com/uniride/entities/Solicitud.java
src/main/java/com/uniride/entities/Usuario.java
src/main/java/com/uniride/entities/Viaje.java
src/main/java/com/uniride/exceptions/DemasiadasSolicitudesException.java
src/main/java/com/uniride/exceptions/GlobalExceptionHandler.java
src/main/java/com/uniride/ocr/PdfWorker.java
src/main/java/com/uniride/repositories/CorreoPendienteRepository.java
src/main/java/com/uniride/repositories/PerfilRepository.java
src/main/java/com/uniride/repositories/RutaRepository.java
src/main/java/com/uniride/repositories/SolicitudRepository.java
src/main/java/com/uniride/repositories/UsuarioRepository.java
src/main/java/com/uniride/repositories/ViajeRepository.java
src/main/java/com/uniride/security/JwtService.java
src/main/java/com/uniride/security/LimiteAutenticacion.java
src/main/java/com/uniride/security/LimiteAuthFilter.java
src/main/java/com/uniride/security/SecurityConfig.java
src/main/java/com/uniride/services/AuthService.java
src/main/java/com/uniride/services/BusquedaService.java
src/main/java/com/uniride/services/CorreoOutboxService.java
src/main/java/com/uniride/services/CorreoReintentos.java
src/main/java/com/uniride/services/EstadisticasService.java
src/main/java/com/uniride/services/HorarioAcademicoService.java
src/main/java/com/uniride/services/NotificacionService.java
src/main/java/com/uniride/services/PerfilService.java
src/main/java/com/uniride/services/RutaService.java
src/main/java/com/uniride/services/SolicitudService.java
src/main/java/com/uniride/services/SolicitudesRecibidasUs14Service.java
src/main/java/com/uniride/services/ViajeService.java
src/main/java/com/uniride/util/Paginacion.java
src/main/java/com/uniride/util/ValidacionUtil.java
src/main/java/db/migration/V3__normalizar_telefonos.java
src/main/resources/application.yml
src/main/resources/db/migration/V1__schema_inicial.sql
src/main/resources/db/migration/V2__integridad_y_hardening.sql
src/main/resources/db/migration/V4__vigencia_correo_otp.sql
src/main/resources/db/migration/V5__participacion_cancelaciones_y_outbox_fk.sql
src/main/resources/db/migration/V6__fk_perfiles_por_tabla.sql
src/test/java/com/uniride/controllers/ErroresProtocoloHttpTest.java
src/test/java/com/uniride/controllers/PaginacionHardeningHttpTest.java
src/test/java/com/uniride/controllers/PerfilHardeningHttpTest.java
src/test/java/com/uniride/controllers/PerfilPersistenciaIntegrationTest.java
src/test/java/com/uniride/repositories/EstadisticasHardeningRepositoryTest.java
src/test/java/com/uniride/repositories/SolicitudRepositoryTest.java
src/test/java/com/uniride/security/LimiteAutenticacionTest.java
src/test/java/com/uniride/services/AuthHardeningTest.java
src/test/java/com/uniride/services/CierreTotal114AutenticacionTest.java
src/test/java/com/uniride/services/CorreoOutboxServiceTest.java
src/test/java/com/uniride/services/EstadisticasServiceTest.java
src/test/java/com/uniride/services/HorarioAcademicoServiceTest.java
src/test/java/com/uniride/services/OcrHardeningTest.java
src/test/java/com/uniride/services/PostgresHardeningIntegrationTest.java
src/test/java/com/uniride/services/PostgresMigracionIntegrationTest.java
src/test/java/com/uniride/services/SolicitudesRecibidasUs14ServiceTest.java
src/test/java/com/uniride/services/ViajeHardeningTest.java
src/test/resources/application-postgres.yml
src/test/resources/application.yml
```

## F. Migraciones

V1..V6 y su procedimiento están en [operacion-hardening.md](operacion-hardening.md).
Se probaron base vacía, baseline explícito versión 1 de esquema existente,
conservación/normalización de un usuario histórico con teléfono `0051...`, aplicación
V2..V6 y reejecución sin cambios. La regresión de migración crea otro esquema con
los mismos nombres de FK y verifica las tres relaciones al esquema correcto. El autor de cancelaciones históricas sigue NULL;
la participación histórica desconocida no se reconstruye artificialmente.
Las colisiones detienen la migración; no se borran usuarios ni actividad para pasarla.
No se aplicaron migraciones a la base real de desarrollo ni a volúmenes existentes.

## G. Pruebas y resultados

Resultados definitivos, Java Temurin 21.0.13+9 y PostgreSQL 16.15 temporal:

| Comprobación | Resultado |
|---|---|
| Baseline `mvnw.cmd test` (Java 25 inicial) | 302 pruebas / 55 clases, 0 fallos, 0 errores, 0 omitidas |
| Final `mvnw.cmd clean test` | 350 pruebas / 66 clases, 0 fallos, 0 errores, 0 omitidas; BUILD SUCCESS |
| Final `mvnw.cmd verify` | 350 pruebas, 0 fallos/errores/omitidas; JAR ejecutable empaquetado; BUILD SUCCESS |
| `mvnw.cmd -Ppostgres-it test` | 6 pruebas / 2 clases, 0 fallos/errores/omitidas; BUILD SUCCESS |
| `docker build -t uniride-hardening-audit:local .` | BUILD SUCCESS, ejecuta también las 350 pruebas con Java 21 |
| Imagen final + PostgreSQL temporal | Base nueva, V1..V6 aplicadas, arranque real y validación JPA; usuario uid 999 sin privilegios |
| Smoke HTTP en imagen final | Login 200; usuarios/me 200; OpenAPI 49 mappings; PDF escaneado 201 con 1 curso importado |
| Dependencias Maven / OSV querybatch | 171 coordenadas/versiones consultadas; 0 coincidencias tras parches |
| `git diff --check`, conflictos y credenciales | Sin errores de whitespace, sin marcadores de conflicto ni patrones de claves privadas versionadas |

Total de pruebas distintas: **356**, en **68 clases**. No se suman ejecuciones
repetidas como si fueran casos distintos. La prueba SMTP usa proveedor simulado;
la entrega real está pendiente según I. CI está definido para ejecutar ambas suites
más build Docker y smoke OCR nativo; no se ejecutó remotamente porque no se hizo push.
El baseline fue 302/55 clases; existen ahora 68 clases, incluidas las 55 originales.
La última repetición con V6 terminó el 06/11/2026 (Lima): PostgreSQL 00:04:21,
`clean test` 00:07:00, `verify` 00:08:15. El smoke posterior de la imagen final
comprobó también versión Flyway 6 sobre una base nueva y los resultados HTTP de la tabla.
Las pruebas nuevas cubren límites exactos 4/12/20/24 h con Clock fijo; autor sin
penalidad, vencimiento que no es cancelación, terceros y desconocidos; contenido PDF;
perfil persistido en ambos endpoints; OTP, cooldown/bloqueo, límites HTTP; SMTP
simulado, descarte de OTP; PDF/TXT adversarios; PostgreSQL y migraciones.

Verificación fallida intermedia: un `verify` se solapó incorrectamente con `clean`
y no pudo empaquetar. Se descartó ese resultado y se ejecutaron las comprobaciones
finales en serie. Otros fallos de pruebas guiaron las correcciones registradas;
no se ocultaron con timeouts mayores ni omisiones. La prueba multiesquema falló
antes de V6 (esperaba 3 FK, encontró 0) y pasó tras la reparación aditiva.

## H. Contratos HTTP

Se conservan 49 mappings, métodos, DTO y roles de acceso; ver
[inventario completo](endpoints.md#índice-rápido) y [29 historias](historias-endpoints.md).

Cambios necesarios y probados: reenvío retorna `usuario: null` para cerrar exposición
de PII; límites de abuso retornan 429; parámetros inválidos retornan 400; creación
y reprogramación exigen >=12 h (409 si falta anticipación); inicio prematuro 409;
errores estándar de protocolo preservan 400/404/405/415. Teléfonos se normalizan a
E.164 y correos a minúsculas. Fechas académicas invertidas son 400.
No se altera Page ni las variantes `/resumen`; no se inventan endpoints de acuerdos.
Las notificaciones de correo pasan a entrega asíncrona posterior al commit.

## I. Riesgos y pendientes operativos

- Envío SMTP real requiere verificación del operador con credenciales y buzón de
  prueba. Se probaron fallos/reintentos con proveedor simulado; no se afirma entrega
  real en Gmail ni se usaron credenciales reales.
- Rate limiting de cuenta/IP y agrupación de avisos son por proceso; múltiples
  réplicas requieren presupuesto compartido en proxy/Redis. OTP/cooldown sí son
  persistentes y bloqueados. SMTP no ofrece exactamente una vez ante caída después
  de aceptar el correo; esta ventana se documenta sin prometer una garantía imposible.
- Antes de despliegue sobre una base existente: backup/restauración, revisión de
  duplicados e inconsistencias y baseline explícito en copia. Los datos reales no
  se inspeccionaron ni alteraron. Clave outbox debe conservarse al rotar JWT.
- No se ejecutó el frontend Angular ni una prueba de carga con SLA de producción.
  Se preservaron rutas/DTO y se verificaron pruebas HTTP y restricciones existentes.
- Auditoría fuente de producción más revisión independiente y pruebas no equivalen
  a una certificación de ausencia absoluta de vulnerabilidades. El informe canónico
  de seguridad registra cinco hallazgos iniciales corregidos y cobertura parcial de
  archivos auxiliares en su momento de cierre. Una revisión auxiliar posterior leyó
  las 68 clases de prueba, sus recursos, documentación/configuración y migraciones;
  detectó el caso multiesquema y las contradicciones documentales corregidas aquí.
  El informe canónico sellado conserva el alcance histórico de su ejecución.

## J. Cierres anteriores

| Cierre | Verificación conservada |
|---|---|
| 11.1 | IntegridadEliminacionViajesServiceTest/RepositoryTest: no borrar actividad, solicitudes, acuerdos ni penalidades |
| 11.2 | AceptacionConcurrenciaCuposServiceTest/IntegrationTest: último cupo y mismo id |
| 11.3 | EstadosConcurrentesViaje113Test, EstadosConcurrentesSolicitud113Test, CancelacionAceptacionConcurrencia113IntegrationTest |
| 11.4 | CierreTotal114Autenticacion/Seguridad/PrivacidadPerfil/MetodoCompensacion/AccesoYMetodos/Errores: roles, privacidad y errores seguros |

Se mantienen JWT de cuentas verificadas, recarga de rol activo, perfiles por rol,
repositorios no expuestos por REST, borradores únicos y permisos de propietario.

## K. Git final

Solo commits locales en `fix/backend-hardening-final`. Sin push, merge, PR,
reset --hard ni force push. Tras el commit de cierre se comprobó árbol limpio y
merge-base `21bb11d`; master/development conservan sus referencias iniciales.
Los contenedores y la red aislados de auditoría se retiran después de las pruebas;
no se toca el volumen PostgreSQL de desarrollo.

## L. Preparación de PR

Las verificaciones técnicas de G pasaron: **listo para revisión mediante PR hacia
development**, sin bloqueo de build o prueba conocido. Los pendientes operativos
de I son explícitos y no deben presentarse como pruebas realizadas. El despliegue
sobre datos existentes requiere el procedimiento de F, aunque el código esté listo
para revisión. No se abre el PR automáticamente.

## M. PR sugerido hacia development

**Título:** Endurecer viajes, autenticación e integridad del backend UniRide

**Descripción:** Corrige plazos y vencimientos de viajes, cancelaciones auditables y
carreras de capacidad/eliminación. Añade controles OTP y de abuso, correo durable,
procesamiento PDF/TXT limitado, migraciones Flyway y CI con PostgreSQL. Conserva
las 29 historias, 49 mappings y cierres 11.1–11.4; documenta cambios de validación
y privacidad. Incluye regresión unitaria, HTTP, repositorios, concurrencia y migración.
Antes de desplegar una base existente, revisar colisiones, respaldar y establecer
baseline explícito; comprobar SMTP real con un buzón de prueba.

**Validación sugerida para el PR:** 350 pruebas estándar y 6 PostgreSQL, cero
fallos/errores/omitidas; `clean test`, `verify`, empaquetado y Docker correctos.
PDF escaneado real importado con HTTP 201, OpenAPI con 49 mappings, 55 clases
originales conservadas. CI remoto y frontend Angular no ejecutados.


## Dependencias contrastadas

Se conservó Spring Boot 4.1.1. Parches: PDFBox 3.0.8, BOM Jackson 2.21.7/3.1.7,
Tomcat 11.0.27 y Bouncy Castle 1.85. Las alertas iniciales eran coincidencias por
versión; algunas exigían características que la aplicación no utiliza. No se
presentan todas como vulnerabilidades explotables del backend. Consulta final
OSV: cero coincidencias en las 171 dependencias Maven resueltas; no es una
certificación sobre dependencias futuras ni un escaneo de todos los paquetes del SO.

Fuentes primarias: [Tomcat](https://tomcat.apache.org/security-11.html),
[Jackson](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wv8q-qhhj-9h54),
[PDFBox](https://pdfbox.apache.org/download.html),
[API OSV](https://google.github.io/osv.dev/post-v1-querybatch/).
El informe canónico de Codex Security corresponde al baseline, con cinco hallazgos
confirmados y remediación descrita; la medición del complemento registró 41.880.144 tokens totales,
41.700.620 de entrada y 41.067.264 de entrada en caché (cinco hilos; métrica de rollout,
no número de pruebas ni estimación de coste).
