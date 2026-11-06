# Trazabilidad de las 29 historias

Reconstrucción desde documentación y controladores; el repositorio no contiene una
especificación original separada con criterios adicionales. El inventario exhaustivo
de **49 mappings** está en [endpoints.md](endpoints.md#índice-rápido).

| US | Capacidad | API / implementación | Evidencia de regresión |
|---|---|---|---|
| 01 | Registro institucional | POST auth/registro; AuthService | CierreTotal114AutenticacionTest, AuthHardeningTest |
| 02 | Login/verificación/reenvío | POST auth/login, verificar, reenviar-codigo | AuthHardeningTest, PostgresHardeningIntegrationTest |
| 03 | Usuario y rol activo | GET usuarios/me, PUT usuarios/rol | AutorizacionRolesTest, PerfilesRolRepositoryTest |
| 04 | Datos personales | GET/PUT perfil | PerfilHardeningHttpTest, PerfilRepositoryTest |
| 05 | Información adicional | PUT perfil/info-adicional | PerfilServiceTest, PerfilControllerHttpTest |
| 06 | Publicar ruta | POST rutas | RutaServiceTest, RutaRepositoryTest |
| 07 | Editar ruta/horario viaje | PUT rutas/{id}, viajes/{id} | RutaEdicionServiceTest, ViajeEdicionServiceTest, EdicionRutaViajeHttpTest |
| 08 | Eliminar ruta lógica | DELETE rutas/{id} | RutaServiceTest, PostgresHardeningIntegrationTest |
| 09 | Mis rutas y estados | GET rutas, rutas/resumen | RutaEstadoUs09ServiceTest, MisRutasUs09ControllerHttpTest |
| 10 | Buscar y ordenar relevancia | GET viajes/buscar y buscar/resumen | BusquedaUs10ServiceTest, BusquedaUs10RepositoryTest |
| 11 | Enviar solicitud | POST solicitudes/viajes/{viajeId} | EstadosConcurrentesSolicitud113Test, SolicitudNotificacionUs25Test |
| 12 | Cancelar solicitud pendiente | PUT solicitudes/{id}/cancelar | MisSolicitudesUs13ServiceTest, EstadosConcurrentesSolicitud113Test |
| 13 | Solicitudes/historial propio | GET solicitudes, resumen, historial | MisSolicitudesUs13ControllerHttpTest, MisSolicitudesUs13RepositoryTest |
| 14 | Solicitudes recibidas/perfil | GET viajes/{id}/solicitudes, solicitudes/recibidas/por-ruta, {id}/detalle, perfil/usuario/{id} | SolicitudesRecibidasUs14ControllerHttpTest, CierreTotal114PrivacidadPerfilTest |
| 15 | Aceptar/generar acuerdo | PUT solicitudes/{id}/aceptar | AceptacionConcurrenciaCuposIntegrationTest, PostgresHardeningIntegrationTest |
| 16 | Rechazar individual/lote | PUT solicitudes/{id}/rechazar, rechazar-multiples | EstadosConcurrentesSolicitud113Test |
| 17 | Confirmación/recordatorio/vencimiento | PUT viajes/{id}/confirmar; schedulers | ViajeServiceTest, ViajeHardeningTest, PostgresHardeningIntegrationTest |
| 18 | Cancelar viaje/penalidad | PUT viajes/{id}/cancelar | ViajeHardeningTest, CancelacionAceptacionConcurrencia113IntegrationTest |
| 19 | Resumen actividad | GET estadisticas | EstadisticasService; queries de Viaje/Solicitud/Penalidad |
| 20 | Frecuencia histórica semanal | GET estadisticas/semana | EstadisticasServiceTest, EstadisticasHardeningRepositoryTest |
| 21 | Ranking conductor | GET estadisticas/rutas | EstadisticasHardeningRepositoryTest |
| 22 | Historial PDF | GET historial/pdf | EstadisticasServiceTest |
| 23 | Importación PDF/TXT | POST perfil/horarios/archivo | HorarioAcademicoServiceTest, OcrHardeningTest |
| 24 | Cursos manuales | POST/GET perfil/cursos, DELETE perfil/cursos/{id} | HorarioAcademicoControllerHttpTest, PaginacionHardeningHttpTest |
| 25 | Notificaciones | Transversal, sin controlador público propio | NotificacionUs25ServiceTest, CorreoOutboxServiceTest |
| 26 | Métodos compensación | GET/POST metodos-compensacion | MetodoCompensacionRepositoryTest |
| 27 | Editar método propio | PUT metodos-compensacion/{id} | CierreTotal114MetodoCompensacionTest |
| 28 | Eliminar método sin referencias | DELETE metodos-compensacion/{id} | CierreTotal114AccesoYMetodosRepositoryTest |
| 29 | Sugerencia contribución | GET viajes/{id}/sugerencia-contribucion | ContribucionBusquedaUs10Test |

Operaciones de soporte: POST/GET rutas/{id}/viajes, DELETE viajes/{id}, PUT
viajes/{id}/iniciar y /completar. Se mantienen en el inventario aunque no tengan
una historia exclusiva. AcuerdoCompensacion se crea BORRADOR en aceptación; no
existe endpoint para confirmar/rechazar acuerdos y no se inventa ese flujo.
