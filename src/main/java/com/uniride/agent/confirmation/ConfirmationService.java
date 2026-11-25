package com.uniride.agent.confirmation;

import com.uniride.agent.AgentConversationStore;
import com.uniride.agent.dto.*;
import com.uniride.dto.requests.*;
import com.uniride.dto.responses.MetodoCompensacionRespuesta;
import com.uniride.dto.responses.RutaRespuesta;
import com.uniride.entities.*;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoCompensacion;
import com.uniride.exceptions.*;
import com.uniride.repositories.*;
import com.uniride.services.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The model prepares operations. The authenticated user resolves the same persisted proposal from either channel. */
@Service
public class ConfirmationService {
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String SNAPSHOT_OBSOLETO =
            "Los datos cambiaron desde la preparación. Cancela esta confirmación y prepara una nueva operación.";
    private final ConfirmationRepository confirmations;
    private final UsuarioService usuarios;
    private final RutaService rutas;
    private final ViajeService viajes;
    private final SolicitudService solicitudes;
    private final MetodoCompensacionService metodos;
    private final RutaRepository rutaRepository;
    private final ViajeRepository viajeRepository;
    private final PerfilRepository perfiles;
    private final EntityManager entityManager;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final Clock clock;
    private final PerfilService perfilService;
    private final HorarioAcademicoService horarioAcademicoService;
    private final CursoRepository cursoRepository;

    public ConfirmationService(ConfirmationRepository confirmations, UsuarioService usuarios, RutaService rutas,
            ViajeService viajes, SolicitudService solicitudes, MetodoCompensacionService metodos,
            RutaRepository rutaRepository, ViajeRepository viajeRepository, PerfilRepository perfiles,
            EntityManager entityManager, ObjectMapper mapper, Validator validator, Clock clock) {
        this(confirmations, usuarios, rutas, viajes, solicitudes, metodos, rutaRepository, viajeRepository,
                perfiles, entityManager, mapper, validator, clock, null, null, null);
    }

    @Autowired
    public ConfirmationService(ConfirmationRepository confirmations, UsuarioService usuarios, RutaService rutas,
            ViajeService viajes, SolicitudService solicitudes, MetodoCompensacionService metodos,
            RutaRepository rutaRepository, ViajeRepository viajeRepository, PerfilRepository perfiles,
            EntityManager entityManager, ObjectMapper mapper, Validator validator, Clock clock,
            PerfilService perfilService, HorarioAcademicoService horarioAcademicoService,
            CursoRepository cursoRepository) {
        this.confirmations = confirmations;
        this.usuarios = usuarios;
        this.rutas = rutas;
        this.viajes = viajes;
        this.solicitudes = solicitudes;
        this.metodos = metodos;
        this.rutaRepository = rutaRepository;
        this.viajeRepository = viajeRepository;
        this.perfiles = perfiles;
        this.entityManager = entityManager;
        this.mapper = mapper;
        this.validator = validator;
        this.clock = clock;
        this.perfilService = perfilService;
        this.horarioAcademicoService = horarioAcademicoService;
        this.cursoRepository = cursoRepository;
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararRuta(RutaRequest request) {
        Usuario usuario = autorizado(TipoOperacion.CREAR_RUTA);
        validar(request);
        String destino = rutas.validarPublicacion(request);
        return guardar(usuario, TipoOperacion.CREAR_RUTA, null, request, mapper.valueToTree(destino),
                "Publicar ruta desde " + request.origen() + " hacia " + destino
                        + ", capacidad " + request.capacidadMaxima() + ". Referencias: " + request.puntosReferencia()
                        + opcional(". Zonas sin parada: ", request.zonasSinParada())
                        + opcional(". Contribución: ", request.notaContribucion()) + ".");
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararViaje(Long rutaId, ViajeRequest request) {
        return prepararViaje(rutaId, request, null);
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararViaje(RutaRespuesta esperada, ViajeRequest request) {
        return prepararViaje(esperada.id(), request, esperada);
    }

    private OperacionResumen prepararViaje(Long rutaId, ViajeRequest request, RutaRespuesta esperada) {
        Usuario usuario = autorizado(TipoOperacion.CREAR_VIAJE);
        validar(request);
        Ruta ruta = viajes.validarCreacion(rutaId, request);
        if (esperada != null && !coincideRuta(ruta, esperada))
            throw new ResourceNotFoundException("La ruta elegida cambió.");
        return guardar(usuario, TipoOperacion.CREAR_VIAJE, rutaId, request, snapshot(ruta),
                "Programar un viaje desde " + ruta.getOrigen() + " hacia " + ruta.getDestino()
                        + " el " + request.fecha() + " a las " + request.hora()
                        + ", capacidad " + ruta.getCapacidadMaxima() + ". Referencias: " + ruta.getPuntosReferencia() + ".");
    }

    private boolean coincideRuta(Ruta ruta, RutaRespuesta esperada) {
        return Objects.equals(ruta.getId(), esperada.id())
                && Objects.equals(ruta.getConductor().getId(), esperada.conductorId())
                && Objects.equals(ruta.getOrigen(), esperada.origen())
                && Objects.equals(ruta.getDestino(), esperada.destino())
                && Objects.equals(ruta.getPuntosReferencia(), esperada.puntosReferencia())
                && Objects.equals(ruta.getZonasSinParada(), esperada.zonasSinParada())
                && Objects.equals(ruta.getNotaContribucion(), esperada.notaContribucion())
                && Objects.equals(ruta.getCapacidadMaxima(), esperada.capacidadMaxima())
                && ruta.getEstado() == esperada.estado();
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararSolicitud(Long viajeId, SolicitudRequest request) {
        Usuario usuario = autorizado(TipoOperacion.SOLICITAR_UNIRSE);
        validar(request);
        Viaje viaje = solicitudes.validarCreacion(viajeId, request);
        return guardar(usuario, TipoOperacion.SOLICITAR_UNIRSE, viajeId, request, snapshot(viaje),
                "Solicitar un cupo desde " + viaje.getRuta().getOrigen() + " hacia " + viaje.getRuta().getDestino()
                        + " el " + viaje.getFecha() + " a las " + viaje.getHora()
                        + ", compensación " + request.preferenciaCompensacion()
                        + opcional(". Mensaje: ", request.mensaje())
                        + ". La solicitud quedará PENDIENTE de aceptación por el conductor.");
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararEdicionRuta(Long rutaId, String nuevoOrigen, Integer capacidadMaxima,
            String puntosReferencia, String zonasSinParada, String notaContribucion) {
        Usuario usuario = autorizado(TipoOperacion.EDITAR_RUTA);
        Ruta ruta = rutaPropia(rutaId, usuario.getId());
        if (ruta.getEstado() == EstadoRuta.ELIMINADA)
            throw new BusinessException("No se puede editar una ruta eliminada");
        RutaRequest request = new RutaRequest(
                nuevoOrigen == null ? ruta.getOrigen() : nuevoOrigen,
                capacidadMaxima == null ? ruta.getCapacidadMaxima() : capacidadMaxima,
                puntosReferencia == null ? ruta.getPuntosReferencia() : puntosReferencia,
                zonasSinParada == null ? ruta.getZonasSinParada() : zonasSinParada,
                notaContribucion == null ? ruta.getNotaContribucion() : notaContribucion);
        validar(request);

        List<String> cambios = new ArrayList<>();
        if (!Objects.equals(ruta.getOrigen(), request.origen())) cambios.add("origen " + request.origen());
        if (ruta.getCapacidadMaxima() != request.capacidadMaxima())
            cambios.add("capacidad " + request.capacidadMaxima());
        if (!Objects.equals(ruta.getPuntosReferencia(), request.puntosReferencia()))
            cambios.add("puntos de referencia " + request.puntosReferencia());
        if (!Objects.equals(ruta.getZonasSinParada(), request.zonasSinParada()))
            cambios.add("zonas sin parada " + (request.zonasSinParada() == null ? "vacías" : request.zonasSinParada()));
        if (!Objects.equals(ruta.getNotaContribucion(), request.notaContribucion()))
            cambios.add("nota de contribución " + (request.notaContribucion() == null ? "vacía" : request.notaContribucion()));
        if (cambios.isEmpty())
            return new OperacionResumen(EstadoOperacion.SIN_CAMBIOS, null, "La ruta ya tiene los valores solicitados.");

        return guardar(usuario, TipoOperacion.EDITAR_RUTA, rutaId, request, snapshot(ruta),
                "Actualizar tu ruta " + ruta.getOrigen() + " → " + ruta.getDestino() + ": "
                        + String.join(", ", cambios) + ". Los pasajeros aceptados serán notificados.");
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararEliminacionRuta(Long rutaId) {
        Usuario usuario = autorizado(TipoOperacion.ELIMINAR_RUTA);
        Ruta ruta = rutaPropia(rutaId, usuario.getId());
        if (ruta.getEstado() == EstadoRuta.ELIMINADA)
            return new OperacionResumen(EstadoOperacion.SIN_CAMBIOS, null, "La ruta ya estaba eliminada.");
        return guardar(usuario, TipoOperacion.ELIMINAR_RUTA, rutaId, mapper.createObjectNode(), snapshot(ruta),
                "Eliminar tu ruta " + ruta.getOrigen() + " → " + ruta.getDestino()
                        + ". Los viajes programados futuros se cancelarán y el historial se conserva.");
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararReprogramacionViaje(Long viajeId, LocalDate fecha, LocalTime hora) {
        Usuario usuario = autorizado(TipoOperacion.REPROGRAMAR_VIAJE);
        Viaje viaje = viajeRepository.findByIdAndRutaConductorId(viajeId, usuario.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));
        if (viaje.getRuta().getEstado() == EstadoRuta.ELIMINADA)
            throw new BusinessException("No se puede reprogramar un viaje de una ruta eliminada");
        if (viaje.getEstado() != EstadoViaje.PROGRAMADO)
            throw new BusinessException("Solo se pueden reprogramar viajes en estado PROGRAMADO");
        ViajeRequest request = new ViajeRequest(fecha, hora);
        validar(request);
        if (!LocalDateTime.of(fecha, hora).isAfter(LocalDateTime.now(clock)))
            throw new CamposInvalidosException("La nueva fecha y hora deben ser futuras");
        if (viaje.getFecha().equals(fecha) && viaje.getHora().equals(hora))
            return new OperacionResumen(EstadoOperacion.SIN_CAMBIOS, null, "El horario no cambió.");
        viajes.validarNuevaSalida(request);
        return guardar(usuario, TipoOperacion.REPROGRAMAR_VIAJE, viajeId, request, snapshot(viaje),
                "Reprogramar tu viaje del " + viaje.getDia() + " " + viaje.getFecha() + " a las " + viaje.getHora()
                        + " (" + viaje.getRuta().getOrigen() + " → " + viaje.getRuta().getDestino()
                        + ") para el " + fecha + " a las " + hora + ". Los pasajeros aceptados serán notificados.");
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararGestionSolicitud(TipoOperacion tipo, Long id, String motivo) {
        if (tipo != TipoOperacion.ACEPTAR_SOLICITUD && tipo != TipoOperacion.RECHAZAR_SOLICITUD) {
            throw new IllegalArgumentException("Se requiere una gestión individual de solicitud");
        }
        Usuario usuario = autorizado(tipo);
        Solicitud solicitud = solicitudes.solicitudRecibida(id);
        if (tipo == TipoOperacion.ACEPTAR_SOLICITUD) solicitudes.validarAceptacion(solicitud);
        else solicitudes.validarSolicitudProcesable(solicitud);
        String accion = tipo == TipoOperacion.ACEPTAR_SOLICITUD ? "Aceptar" : "Rechazar";
        var request = new RechazarRequest(tipo == TipoOperacion.RECHAZAR_SOLICITUD ? motivoRechazo(motivo) : null);
        String detalle = accion + " la solicitud de " + solicitud.getPasajero().getNombre() + " "
                + solicitud.getPasajero().getApellidos() + " para " + describirViaje(solicitud.getViaje()) + "."
                + (tipo == TipoOperacion.ACEPTAR_SOLICITUD
                        ? " Se ocupará un cupo y se creará un borrador de acuerdo de compensación."
                        : opcional(" Motivo: ", request.motivo()))
                + " Se avisará al pasajero.";
        return guardar(usuario, tipo, id, request, snapshotSolicitudRecibida(solicitud), detalle);
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararRechazoPendientes(Long viajeId, String motivo, List<Long> elegidas) {
        var tipo = TipoOperacion.RECHAZAR_SOLICITUDES_PENDIENTES;
        Usuario usuario = autorizado(tipo);
        Viaje viaje = viajePropio(viajeId, usuario);
        var pendientes = solicitudes.pendientesRecibidas(viaje);
        boolean todas = elegidas.isEmpty();
        if (!todas) {
            var idsElegidos = java.util.Set.copyOf(elegidas);
            pendientes = pendientes.stream().filter(s -> idsElegidos.contains(s.getId())).toList();
            if (pendientes.size() != idsElegidos.size()) {
                throw new BusinessException("Las solicitudes elegidas deben estar pendientes en el mismo viaje.");
            }
        }
        if (pendientes.isEmpty()) {
            return new OperacionResumen(EstadoOperacion.SIN_CAMBIOS, null,
                    "Ese viaje no tiene solicitudes pendientes.");
        }
        pendientes.forEach(solicitudes::validarSolicitudProcesable);
        var request = new RechazoPendientes(idsSolicitudes(pendientes), motivoRechazo(motivo), todas);
        return guardar(usuario, tipo, viajeId, request, snapshotGestionViaje(viaje),
                "Rechazar " + request.ids().size() + " solicitudes pendientes de " + describirViaje(viaje)
                        + (todas ? " (todas las pendientes)" : " (solicitudes " + request.ids() + ")")
                        + ". Se avisará a los pasajeros afectados." + opcional(" Motivo: ", request.motivo()));
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararGestionViaje(TipoOperacion tipo, Long id) {
        Usuario usuario = autorizado(tipo);
        Viaje viaje = viajePropio(id, usuario);
        Object parametros = mapper.createObjectNode();
        String viajeDescrito = describirViaje(viaje);
        String descripcion = switch (tipo) {
            case CONFIRMAR_VIAJE -> {
                viajes.validarConfirmacion(viaje);
                yield "Confirmar " + viajeDescrito + ". Se avisará a los pasajeros aceptados.";
            }
            case INICIAR_VIAJE -> {
                viajes.validarInicio(viaje);
                yield "Iniciar " + viajeDescrito + ". El viaje pasará a EN_PROGRESO y se avisará a los pasajeros aceptados.";
            }
            case COMPLETAR_VIAJE -> {
                viajes.validarFinalizacion(viaje);
                yield "Completar " + viajeDescrito + ". El viaje se registrará como COMPLETADO y se avisará a los pasajeros aceptados.";
            }
            case CANCELAR_VIAJE -> {
                var impacto = viajes.evaluarCancelacion(viaje);
                parametros = impacto;
                String penalidad = impacto.penalidad() == null ? "No se aplicará una penalidad."
                        : "Se registrará una penalidad " + impacto.penalidad() + " en tu perfil.";
                yield "Cancelar " + viajeDescrito + ". " + penalidad
                        + " Solicitudes pendientes o aceptadas que pasarán a CANCELADA: " + impacto.solicitudes().size()
                        + ". Pasajeros que recibirán un aviso: " + impacto.pasajerosAvisados() + ".";
            }
            default -> throw new IllegalArgumentException("Se requiere una gestión de viaje");
        };
        return guardar(usuario, tipo, id, parametros, snapshotGestionViaje(viaje), descripcion);
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararCancelacionSolicitudes(List<SolicitudResumen> solicitudes) {
        Usuario usuario = autorizado(TipoOperacion.CANCELAR_SOLICITUD);
        if (solicitudes == null || solicitudes.isEmpty())
            return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null, "No tienes solicitudes pendientes que cancelar.");
        List<Long> ids = solicitudes.stream().map(SolicitudResumen::id).toList();
        String detalle = String.join("; ", solicitudes.stream()
                .map(s -> s.origen() + " → " + s.destino() + " (" + s.fecha() + " " + s.hora() + ")").toList());
        return guardar(usuario, TipoOperacion.CANCELAR_SOLICITUD, null, ids, mapper.createObjectNode(),
                "Cancelar " + (ids.size() == 1 ? "tu solicitud pendiente: " : ids.size() + " solicitudes pendientes: ")
                        + detalle + ".");
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararEditarPerfil(PerfilRequest request, String resumen) {
        Usuario usuario = autorizado(TipoOperacion.EDITAR_PERFIL);
        validar(request);
        if (request.fechaFinClases().isBefore(request.fechaInicioClases())) {
            throw new CamposInvalidosException("La fecha de fin de clases no puede ser anterior al inicio");
        }
        if (request.metodoCompensacionFavorito() == null && usuario.getRolPrincipal() == Rol.CONDUCTOR) {
            throw new CamposInvalidosException("El método de compensación favorito es obligatorio para conductores");
        }
        Perfil perfil = perfiles.findByUsuarioId(usuario.getId()).orElse(null);
        String descripcion = resumen == null || resumen.isBlank() ? "Actualizar tu perfil personal." : resumen;
        return guardar(usuario, TipoOperacion.EDITAR_PERFIL, perfil == null ? null : perfil.getId(),
                request, snapshotPerfil(usuario, perfil), descripcion);
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararEditarInfoAdicional(InfoAdicionalRequest request, String resumen) {
        Usuario usuario = autorizado(TipoOperacion.EDITAR_INFO_ADICIONAL);
        validar(request);
        Perfil perfil = perfiles.findByUsuarioId(usuario.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Primero completa tu perfil personal"));
        if (request.gustos() == null && request.hobbies() == null && request.datosCuriosos() == null) {
            throw new CamposInvalidosException("Debes indicar al menos un campo para actualizar");
        }
        String gustos = valorResultante(request.gustos(), perfil.getGustos());
        String hobbies = valorResultante(request.hobbies(), perfil.getHobbies());
        String datosCuriosos = valorResultante(request.datosCuriosos(), perfil.getDatosCuriosos());
        List<String> faltantes = new ArrayList<>();
        if (gustos == null || gustos.isBlank()) faltantes.add("gustos");
        if (hobbies == null || hobbies.isBlank()) faltantes.add("hobbies");
        if (datosCuriosos == null || datosCuriosos.isBlank()) faltantes.add("datosCuriosos");
        if (!faltantes.isEmpty()) {
            throw new CamposInvalidosException("Completa los campos obligatorios: " + String.join(", ", faltantes));
        }
        String descripcion = resumen == null || resumen.isBlank()
                ? "Actualizar tu información adicional." : resumen;
        return guardar(usuario, TipoOperacion.EDITAR_INFO_ADICIONAL, perfil.getId(),
                request, snapshotInfoAdicional(perfil), descripcion);
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararCambiarRol(CambiarRolRequest request) {
        Usuario usuario = autorizado(TipoOperacion.CAMBIAR_ROL);
        validar(request);
        if (usuario.getRolPrincipal() == request.rol()) {
            throw new BusinessException("Tu rol activo ya es " + request.rol() + ".");
        }
        return guardar(usuario, TipoOperacion.CAMBIAR_ROL, usuario.getId(), request, snapshotRol(usuario),
                "Cambiar tu rol activo de " + usuario.getRolPrincipal() + " a " + request.rol() + ".");
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararRegistrarCurso(CursoRequest request) {
        Usuario usuario = autorizado(TipoOperacion.REGISTRAR_CURSO);
        validar(request);
        if (!request.horaFin().isAfter(request.horaInicio())) {
            throw new CamposInvalidosException("La hora de fin debe ser posterior a la hora de inicio");
        }
        String diaNormalizado = normalizarDia(request.dia());
        for (Curso existente : cursoRepository.buscarPorUsuario(usuario.getId(), Pageable.unpaged())) {
            if (normalizarDia(existente.getDia()).equals(diaNormalizado)
                    && existente.getHoraInicio().isBefore(request.horaFin())
                    && existente.getHoraFin().isAfter(request.horaInicio())) {
                throw new BusinessException("Conflicto de horario con el curso «" + existente.getNombre() + "» ("
                        + existente.getDia() + " de " + existente.getHoraInicio() + " a "
                        + existente.getHoraFin() + ").");
            }
        }
        return guardar(usuario, TipoOperacion.REGISTRAR_CURSO, null, request,
                mapper.createObjectNode().put("usuarioId", usuario.getId()),
                "Registrar el curso «" + request.nombre().trim() + "» el " + request.dia()
                        + " de " + request.horaInicio() + " a " + request.horaFin() + ".");
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararEliminarCurso(Long cursoId) {
        Usuario usuario = autorizado(TipoOperacion.ELIMINAR_CURSO);
        Curso curso = cursoRepository.findById(cursoId)
                .filter(c -> c.getHorarioAcademico().getUsuario().getId().equals(usuario.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Curso no encontrado"));
        return guardar(usuario, TipoOperacion.ELIMINAR_CURSO, curso.getId(), curso.getId(), snapshotCurso(curso),
                "Eliminar el curso «" + curso.getNombre() + "» (" + curso.getDia() + " "
                        + curso.getHoraInicio() + " - " + curso.getHoraFin() + ") de tu horario académico.");
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararMetodo(MetodoCompensacionRequest request) {
        Usuario usuario = autorizado(TipoOperacion.REGISTRAR_METODO);
        validar(request);
        metodos.validarRegistro(request);
        return guardar(usuario, TipoOperacion.REGISTRAR_METODO, null, request, mapper.createObjectNode(),
                "Registrar tu método " + request.tipo() + ": " + request.descripcion().trim() + ".");
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararActualizacionMetodo(TipoCompensacion tipoActual, String descripcionActual,
            TipoCompensacion nuevoTipo, String nuevaDescripcion) {
        return prepararGestionMetodo(TipoOperacion.ACTUALIZAR_METODO, tipoActual, descripcionActual,
                new MetodoCompensacionRequest(nuevoTipo, nuevaDescripcion, null));
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararEstadoMetodo(TipoCompensacion tipoActual, String descripcionActual, Boolean activo) {
        return prepararGestionMetodo(TipoOperacion.CAMBIAR_ESTADO_METODO, tipoActual, descripcionActual,
                new MetodoCompensacionRequest(null, null, activo));
    }

    @Transactional(timeout = 10)
    public OperacionResumen prepararEliminacionMetodo(TipoCompensacion tipoActual, String descripcionActual) {
        return prepararGestionMetodo(TipoOperacion.ELIMINAR_METODO, tipoActual, descripcionActual, null);
    }

    private OperacionResumen prepararGestionMetodo(TipoOperacion tipo, TipoCompensacion tipoActual,
            String descripcionActual, MetodoCompensacionRequest cambios) {
        Usuario usuario = autorizado(tipo);
        if (tipoActual == null && (descripcionActual == null || descripcionActual.isBlank())) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Indica el tipo o la descripción del método de compensación.");
        }
        if (tipo == TipoOperacion.ACTUALIZAR_METODO && cambios.tipo() == null && cambios.descripcion() == null) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "¿Qué quieres cambiar en tu método de compensación? Indica el nuevo tipo o la nueva descripción.");
        }
        if (tipo == TipoOperacion.CAMBIAR_ESTADO_METODO && cambios.activo() == null) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "¿Quieres activar o desactivar el método de compensación?");
        }

        // Serialize selection and snapshot with both REST and confirmed method changes.
        entityManager.refresh(usuario, LockModeType.PESSIMISTIC_WRITE);
        exigirRol(usuario, tipo);
        var coincidencias = metodos.buscarCoincidenciasPropias(tipoActual, descripcionActual);
        if (coincidencias.isEmpty()) {
            return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null, "No encontré un método de compensación propio que coincida.");
        }
        if (coincidencias.size() > 1) {
            return new OperacionResumen(EstadoOperacion.AMBIGUO, null,
                    "Tienes varios métodos de compensación que coinciden. Indica el tipo y la descripción exacta del que deseas cambiar.");
        }
        MetodoCompensacionRespuesta actual = coincidencias.getFirst();
        JsonNode snapshot = snapshotMetodo(actual.id(), usuario.getId(), actual.tipo(), actual.descripcion(), actual.activo());
        String anterior = describirMetodo(actual.tipo(), actual.descripcion(), actual.activo());

        if (tipo == TipoOperacion.ELIMINAR_METODO) {
            metodos.validarEliminacion(actual.id());
            return guardarBajoBloqueo(usuario, tipo, actual.id(), mapper.createObjectNode(), snapshot,
                    "Eliminar tu método " + anterior + ".");
        }

        var request = new MetodoCompensacionRequest(cambios.tipo() == null ? actual.tipo() : cambios.tipo(),
                cambios.descripcion() == null ? actual.descripcion() : cambios.descripcion().trim(),
                cambios.activo() == null ? actual.activo() : cambios.activo());
        validar(request);
        if (request.tipo() == actual.tipo() && request.descripcion().equals(actual.descripcion())
                && request.activo() == actual.activo()) {
            return new OperacionResumen(EstadoOperacion.SIN_CAMBIOS, null,
                    "Tu método de compensación ya tiene los valores solicitados.");
        }
        metodos.validarActualizacion(actual.id(), request);
        String accion = tipo == TipoOperacion.ACTUALIZAR_METODO ? "Actualizar" : request.activo() ? "Activar" : "Desactivar";
        String posterior = describirMetodo(request.tipo(), request.descripcion(), request.activo());
        return guardarBajoBloqueo(usuario, tipo, actual.id(), request, snapshot,
                accion + " tu método. Antes era " + anterior + ". Quedará como " + posterior + ".");
    }

    @Transactional(timeout = 10)
    public ConfirmacionResumen obtener(UUID id) {
        PendingConfirmation pending = propia(id);
        expirarSiCorresponde(pending);
        return resumen(pending);
    }

    @Transactional(timeout = 10)
    public ConfirmacionResumen cancelar(UUID id) {
        return cancelar(propia(id));
    }

    private ConfirmacionResumen cancelar(PendingConfirmation pending) {
        expirarSiCorresponde(pending);
        if (pending.getEstado() == EstadoConfirmacion.EJECUTADA)
            throw new BusinessException("La operación ya fue ejecutada; no se puede cancelar la confirmación.");
        if (pending.getEstado() == EstadoConfirmacion.PENDIENTE) pending.setEstado(EstadoConfirmacion.CANCELADA);
        return resumen(pending);
    }

    @Transactional(timeout = 10)
    public ConfirmacionResumen confirmar(UUID id) {
        return confirmar(propia(id));
    }

    /** Reads the previous state and resolves under the same row lock, including when the app races the chat. */
    @Transactional(timeout = 10)
    public ResolucionConfirmacion resolverDesdeChat(UUID id, boolean confirmar) {
        PendingConfirmation pending = propia(id);
        expirarSiCorresponde(pending);
        return switch (pending.getEstado()) {
            case EJECUTADA -> new ResolucionConfirmacion(ResolucionConfirmacion.Estado.YA_EJECUTADA, resumen(pending));
            case CANCELADA -> new ResolucionConfirmacion(ResolucionConfirmacion.Estado.YA_CANCELADA, resumen(pending));
            case EXPIRADA -> new ResolucionConfirmacion(ResolucionConfirmacion.Estado.EXPIRADA, resumen(pending));
            case PENDIENTE -> {
                ConfirmacionResumen resultado = confirmar ? confirmar(pending) : cancelar(pending);
                var estado = switch (resultado.estado()) {
                    case EJECUTADA -> ResolucionConfirmacion.Estado.EJECUTADA;
                    case CANCELADA -> ResolucionConfirmacion.Estado.CANCELADA;
                    case EXPIRADA -> ResolucionConfirmacion.Estado.EXPIRADA;
                    case PENDIENTE -> throw new IllegalStateException("La decisión no resolvió la confirmación");
                };
                yield new ResolucionConfirmacion(estado, resultado);
            }
        };
    }

    private ConfirmacionResumen confirmar(PendingConfirmation pending) {
        // A replay returns the committed receipt, without invoking any business service.
        if (pending.getEstado() == EstadoConfirmacion.EJECUTADA) return resumen(pending);
        expirarSiCorresponde(pending);
        if (pending.getEstado() == EstadoConfirmacion.EXPIRADA) return resumen(pending);
        if (pending.getEstado() == EstadoConfirmacion.CANCELADA)
            throw new BusinessException("La confirmación fue cancelada. Prepara una nueva operación.");

        Usuario usuario = usuarios.usuarioActual();
        // FOR SHARE keeps the current role stable while remaining compatible with notification FKs.
        // Compensation changes share an exclusive owner lock with REST before any method lock.
        // Acquire it directly to avoid lock upgrades and serialize checks across different methods.
        entityManager.refresh(usuario, pending.getTipo() == TipoOperacion.REGISTRAR_METODO
                || pending.getTipo().modificaMetodoExistente()
                || pending.getTipo() == TipoOperacion.CAMBIAR_ROL
                || pending.getTipo() == TipoOperacion.EDITAR_PERFIL
                ? LockModeType.PESSIMISTIC_WRITE : LockModeType.PESSIMISTIC_READ);
        exigirRol(usuario, pending.getTipo());
        OperacionResumen result = ejecutar(pending, usuario);
        pending.setResultado(mapper.writeValueAsString(result));
        pending.setEstado(EstadoConfirmacion.EJECUTADA);
        pending.setEjecutada(clock.instant());
        confirmations.flush();
        return resumen(pending);
    }

    private OperacionResumen ejecutar(PendingConfirmation pending, Usuario usuario) {
        switch (pending.getTipo()) {
            case CREAR_RUTA -> {
                RutaRequest request = mapper.readValue(pending.getParametros(), RutaRequest.class);
                validar(request);
                Perfil perfil = perfiles.findByUsuarioId(usuario.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Perfil no encontrado"));
                entityManager.refresh(perfil, LockModeType.PESSIMISTIC_READ);
                comprobarSnapshot(pending, mapper.valueToTree(rutas.validarPublicacion(request)));
                var created = rutas.publicar(request);
                return new OperacionResumen(EstadoOperacion.EXITO, created.id(),
                        "Ruta " + created.origen() + " → " + created.destino() + " publicada.");
            }
            case CREAR_VIAJE -> {
                ViajeRequest request = mapper.readValue(pending.getParametros(), ViajeRequest.class);
                validar(request);
                Ruta ruta = rutaRepository.findByIdAndConductorId(pending.getRecursoId(), usuario.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Ruta no encontrada"));
                entityManager.refresh(ruta, LockModeType.PESSIMISTIC_WRITE);
                comprobarSnapshot(pending, snapshot(ruta));
                viajes.validarCreacion(ruta.getId(), request);
                var created = viajes.crear(ruta.getId(), request);
                return new OperacionResumen(EstadoOperacion.EXITO, created.id(),
                        "Viaje programado para el " + created.fecha() + " a las " + created.hora() + ".");
            }
            case SOLICITAR_UNIRSE -> {
                SolicitudRequest request = mapper.readValue(pending.getParametros(), SolicitudRequest.class);
                validar(request);
                Viaje viaje = viajeRepository.findById(pending.getRecursoId())
                        .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));
                // Same order as route editing: route before trip. Refresh after acquiring locks.
                Ruta ruta = viaje.getRuta();
                entityManager.refresh(ruta, LockModeType.PESSIMISTIC_READ);
                entityManager.refresh(viaje, LockModeType.PESSIMISTIC_WRITE);
                comprobarSnapshot(pending, snapshot(viaje));
                // Pure validation also prevents REQUIRES_NEW duplicate notices under these locks.
                solicitudes.validarCreacion(viaje.getId(), request);
                var created = solicitudes.crear(viaje.getId(), request);
                return new OperacionResumen(EstadoOperacion.EXITO, created.id(),
                        "Solicitud enviada. Queda PENDIENTE de aceptación por el conductor.");
            }
            case EDITAR_RUTA -> {
                RutaRequest request = mapper.readValue(pending.getParametros(), RutaRequest.class);
                validar(request);
                Ruta ruta = rutaRepository.findByIdAndConductorId(pending.getRecursoId(), usuario.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Ruta no encontrada"));
                comprobarSnapshot(pending, snapshot(ruta));
                var actualizada = rutas.actualizar(ruta.getId(), request, true);
                return new OperacionResumen(EstadoOperacion.EXITO, actualizada.id(),
                        "Ruta " + actualizada.origen() + " → " + actualizada.destino() + " actualizada.");
            }
            case ELIMINAR_RUTA -> {
                Ruta ruta = rutaRepository.findByIdAndConductorId(pending.getRecursoId(), usuario.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Ruta no encontrada"));
                comprobarSnapshot(pending, snapshot(ruta));
                String detalle = "Ruta " + ruta.getOrigen() + " → " + ruta.getDestino() + " eliminada.";
                rutas.eliminar(ruta.getId(), true);
                return new OperacionResumen(EstadoOperacion.EXITO, ruta.getId(), detalle);
            }
            case REPROGRAMAR_VIAJE -> {
                ViajeRequest request = mapper.readValue(pending.getParametros(), ViajeRequest.class);
                validar(request);
                Viaje viaje = viajeRepository.bloquearParaConductor(pending.getRecursoId(), usuario.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));
                comprobarSnapshot(pending, snapshot(viaje));
                var actualizado = viajes.actualizar(viaje.getId(), request, true);
                return new OperacionResumen(EstadoOperacion.EXITO, actualizado.id(),
                        "Viaje reprogramado para el " + actualizado.fecha() + " a las " + actualizado.hora() + ".");
            }
            case CANCELAR_SOLICITUD -> {
                Long[] ids = mapper.readValue(pending.getParametros(), Long[].class);
                for (Long id : ids) solicitudes.cancelar(id);
                return new OperacionResumen(EstadoOperacion.EXITO, null,
                        ids.length == 1 ? "Cancelé tu solicitud pendiente." : "Cancelé " + ids.length + " solicitudes pendientes.");
            }
            case ACEPTAR_SOLICITUD, RECHAZAR_SOLICITUD -> {
                return ejecutarGestionSolicitud(pending, usuario);
            }
            case RECHAZAR_SOLICITUDES_PENDIENTES -> {
                Viaje viaje = bloquearViajePropio(pending.getRecursoId(), usuario);
                comprobarSnapshot(pending, snapshotGestionViaje(viaje));
                var request = mapper.readValue(pending.getParametros(), RechazoPendientes.class);
                var actuales = java.util.Set.copyOf(idsSolicitudes(solicitudes.pendientesRecibidas(viaje)));
                if (!actuales.containsAll(request.ids()) || request.todas() && actuales.size() != request.ids().size()) {
                    throw new BusinessException(SNAPSHOT_OBSOLETO);
                }
                var resultados = solicitudes.rechazarMultiple(new RechazarMultipleRequest(request.ids(), request.motivo()));
                return new OperacionResumen(EstadoOperacion.EXITO, viaje.getId(),
                        "Solicitudes rechazadas: " + resultados.size() + ". Se registraron los avisos correspondientes.");
            }
            case CONFIRMAR_VIAJE, INICIAR_VIAJE, COMPLETAR_VIAJE, CANCELAR_VIAJE -> {
                return ejecutarGestionViaje(pending, usuario);
            }
            case REGISTRAR_METODO -> {
                MetodoCompensacionRequest request = mapper.readValue(pending.getParametros(), MetodoCompensacionRequest.class);
                validar(request);
                metodos.validarRegistro(request);
                var created = metodos.registrar(request);
                return new OperacionResumen(EstadoOperacion.EXITO, created.id(),
                        "Método " + created.tipo() + ": " + created.descripcion() + " registrado.");
            }
            case ACTUALIZAR_METODO, CAMBIAR_ESTADO_METODO, ELIMINAR_METODO -> {
                return ejecutarGestionMetodo(pending);
            }
            case EDITAR_PERFIL -> {
                PerfilRequest request = mapper.readValue(pending.getParametros(), PerfilRequest.class);
                validar(request);
                Perfil perfil = perfiles.findByUsuarioId(usuario.getId()).orElse(null);
                if (perfil != null) entityManager.refresh(perfil, LockModeType.PESSIMISTIC_WRITE);
                comprobarSnapshot(pending, snapshotPerfil(usuario, perfil));
                var guardado = perfilService.guardarDatosPersonales(request);
                return new OperacionResumen(EstadoOperacion.EXITO, guardado.id(),
                        "Tu perfil personal fue actualizado correctamente.");
            }
            case EDITAR_INFO_ADICIONAL -> {
                InfoAdicionalRequest request = mapper.readValue(pending.getParametros(), InfoAdicionalRequest.class);
                validar(request);
                Perfil perfil = perfiles.findByUsuarioId(usuario.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Primero completa tu perfil personal"));
                entityManager.refresh(perfil, LockModeType.PESSIMISTIC_WRITE);
                comprobarSnapshot(pending, snapshotInfoAdicional(perfil));
                var guardado = perfilService.guardarInfoAdicional(request);
                return new OperacionResumen(EstadoOperacion.EXITO, guardado.id(),
                        "Tu información adicional fue actualizada correctamente.");
            }
            case CAMBIAR_ROL -> {
                CambiarRolRequest request = mapper.readValue(pending.getParametros(), CambiarRolRequest.class);
                validar(request);
                comprobarSnapshot(pending, snapshotRol(usuario));
                usuarios.cambiarRol(request);
                return new OperacionResumen(EstadoOperacion.EXITO, usuario.getId(),
                        "Tu rol activo cambió a " + request.rol()
                                + ". Inicia una nueva conversación para usar las herramientas de este rol.");
            }
            case REGISTRAR_CURSO -> {
                CursoRequest request = mapper.readValue(pending.getParametros(), CursoRequest.class);
                validar(request);
                var creados = horarioAcademicoService.guardarCursos(List.of(request));
                var creado = creados.getFirst();
                return new OperacionResumen(EstadoOperacion.EXITO, creado.id(),
                        "Curso «" + creado.nombre() + "» registrado el " + creado.dia()
                                + " de " + creado.horaInicio() + " a " + creado.horaFin() + ".");
            }
            case ELIMINAR_CURSO -> {
                Curso curso = cursoRepository.findById(pending.getRecursoId())
                        .filter(c -> c.getHorarioAcademico().getUsuario().getId().equals(usuario.getId()))
                        .orElseThrow(() -> new ResourceNotFoundException("Curso no encontrado"));
                entityManager.refresh(curso, LockModeType.PESSIMISTIC_WRITE);
                comprobarSnapshot(pending, snapshotCurso(curso));
                String detalle = "Curso «" + curso.getNombre() + "» eliminado de tu horario académico.";
                horarioAcademicoService.eliminarCurso(curso.getId());
                return new OperacionResumen(EstadoOperacion.EXITO, curso.getId(), detalle);
            }
        }
        throw new IllegalStateException("Tipo de operación no soportado");
    }

    private OperacionResumen ejecutarGestionSolicitud(PendingConfirmation pending, Usuario usuario) {
        Long id = pending.getRecursoId();
        bloquearViajePropio(solicitudes.viajeDeSolicitudRecibida(id), usuario);
        Solicitud actual = solicitudes.solicitudRecibida(id);
        comprobarSnapshot(pending, snapshotSolicitudRecibida(actual));
        boolean aceptar = pending.getTipo() == TipoOperacion.ACEPTAR_SOLICITUD;
        if (aceptar) {
            solicitudes.validarAceptacion(actual);
            solicitudes.aceptar(id);
        } else {
            solicitudes.validarSolicitudProcesable(actual);
            solicitudes.rechazar(id, mapper.readValue(pending.getParametros(), RechazarRequest.class));
        }
        return new OperacionResumen(EstadoOperacion.EXITO, id, aceptar
                ? "Solicitud aceptada. Se reservó un cupo, se generó el borrador de acuerdo y se registró el aviso."
                : "Solicitud rechazada. Se registró el aviso al pasajero.");
    }

    private OperacionResumen ejecutarGestionViaje(PendingConfirmation pending, Usuario usuario) {
        Viaje viaje = bloquearViajePropio(pending.getRecursoId(), usuario);
        comprobarSnapshot(pending, snapshotGestionViaje(viaje));
        Long id = viaje.getId();
        var resultado = switch (pending.getTipo()) {
            case CONFIRMAR_VIAJE -> viajes.confirmar(id, true);
            case INICIAR_VIAJE -> viajes.iniciar(id);
            case COMPLETAR_VIAJE -> viajes.completar(id);
            case CANCELAR_VIAJE -> viajes.cancelar(id,
                    mapper.readValue(pending.getParametros(), ViajeService.ImpactoCancelacion.class));
            default -> throw new IllegalStateException("Operación de viaje no soportada");
        };
        String detalle = pending.getTipo() == TipoOperacion.CONFIRMAR_VIAJE
                ? "Viaje confirmado. Se registraron los avisos para los pasajeros aceptados."
                : resultado.mensaje();
        return new OperacionResumen(EstadoOperacion.EXITO, id, detalle);
    }

    private Viaje viajePropio(Long id, Usuario usuario) {
        return viajeRepository.findByIdAndRutaConductorId(id, usuario.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));
    }

    private Viaje bloquearViajePropio(Long id, Usuario usuario) {
        return viajeRepository.bloquearParaConductor(id, usuario.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));
    }

    private String describirViaje(Viaje viaje) {
        return "el viaje de " + viaje.getRuta().getOrigen() + " a " + viaje.getRuta().getDestino()
                + " del " + viaje.getFecha() + " a las " + viaje.getHora();
    }

    private String motivoRechazo(String motivo) {
        if (motivo == null || motivo.isBlank()) return null;
        if (motivo.strip().length() > 255) {
            throw new CamposInvalidosException("El motivo de rechazo admite hasta 255 caracteres.");
        }
        AgentConversationStore.validateNoSecrets(motivo);
        return motivo.strip();
    }

    private List<Long> idsSolicitudes(List<Solicitud> pendientes) {
        return pendientes.stream().map(Solicitud::getId).sorted().toList();
    }

    private record RechazoPendientes(List<Long> ids, String motivo, boolean todas) { }

    private JsonNode snapshotGestionViaje(Viaje viaje) {
        return mapper.createObjectNode()
                .put("id", viaje.getId()).put("origen", viaje.getRuta().getOrigen())
                .put("destino", viaje.getRuta().getDestino()).put("fecha", viaje.getFecha().toString())
                .put("hora", viaje.getHora().toString()).put("estado", viaje.getEstado().name())
                .put("confirmado", viaje.isConfirmado());
    }

    private JsonNode snapshotSolicitudRecibida(Solicitud solicitud) {
        var data = mapper.createObjectNode()
                .put("id", solicitud.getId()).put("pasajero", solicitud.getPasajero().getId())
                .put("nombre", solicitud.getPasajero().getNombre()).put("apellidos", solicitud.getPasajero().getApellidos())
                .put("estado", solicitud.getEstado().name()).put("preferencia", solicitud.getPreferenciaCompensacion().name());
        data.set("viaje", snapshotGestionViaje(solicitud.getViaje()));
        return data;
    }

    private OperacionResumen ejecutarGestionMetodo(PendingConfirmation pending) {
        MetodoCompensacion metodo = metodos.bloquearMetodoPropio(pending.getRecursoId())
                .orElseThrow(() -> new BusinessException(SNAPSHOT_OBSOLETO));
        comprobarSnapshot(pending, snapshotMetodo(metodo.getId(), metodo.getUsuario().getId(), metodo.getTipo(),
                metodo.getDescripcion(), metodo.isActivo()));

        if (pending.getTipo() == TipoOperacion.ELIMINAR_METODO) {
            var eliminado = metodos.eliminar(metodo.getId());
            return new OperacionResumen(EstadoOperacion.EXITO, metodo.getId(), eliminado.get("mensaje"));
        }
        var request = mapper.readValue(pending.getParametros(), MetodoCompensacionRequest.class);
        validar(request);
        var actualizado = metodos.actualizar(metodo.getId(), request);
        return new OperacionResumen(EstadoOperacion.EXITO, actualizado.id(),
                "Método " + describirMetodo(actualizado.tipo(), actualizado.descripcion(), actualizado.activo()) + " actualizado.");
    }

    private OperacionResumen guardar(Usuario usuario, TipoOperacion tipo, Long recursoId,
            Object parameters, JsonNode snapshot, String description) {
        // Only preparation takes an exclusive user lock. It calls no notifying/write business service.
        entityManager.refresh(usuario, LockModeType.PESSIMISTIC_WRITE);
        exigirRol(usuario, tipo);
        return guardarBajoBloqueo(usuario, tipo, recursoId, parameters, snapshot, description);
    }

    private OperacionResumen guardarBajoBloqueo(Usuario usuario, TipoOperacion tipo, Long recursoId,
            Object parameters, JsonNode snapshot, String description) {
        if (confirmations.countByUsuarioIdAndEstadoAndExpiraAfter(usuario.getId(),
                EstadoConfirmacion.PENDIENTE, clock.instant()) >= 20)
            throw new BusinessException("Tienes 20 operaciones pendientes. Confirma, cancela o espera su expiración.");
        AgentConversationStore.validateNoSecrets(description);
        AgentConversationStore.validateNoSecrets(snapshot.toString());
        PendingConfirmation pending = new PendingConfirmation();
        pending.setId(UUID.randomUUID());
        pending.setUsuario(usuario);
        pending.setTipo(tipo);
        pending.setEstado(EstadoConfirmacion.PENDIENTE);
        pending.setParametros(mapper.writeValueAsString(parameters));
        pending.setSnapshot(snapshot.toString());
        pending.setResumen(description);
        pending.setRecursoId(recursoId);
        pending.setCreada(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        pending.setExpira(pending.getCreada().plus(TTL));
        IntentoPreparacion.marcarPersistencia();
        confirmations.save(pending);
        ConfirmacionResumen response = resumen(pending);
        return new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null,
                description + " ¿Confirmas esta operación?", response);
    }

    private PendingConfirmation propia(UUID id) {
        Usuario usuario = usuarios.usuarioActual();
        return confirmations.bloquearPropia(id, usuario.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Confirmación no encontrada"));
    }

    private Ruta rutaPropia(Long id, Long usuarioId) {
        return rutaRepository.findById(id)
                .filter(ruta -> ruta.getConductor().getId().equals(usuarioId))
                .orElseThrow(() -> new ResourceNotFoundException("Ruta no encontrada"));
    }

    private void expirarSiCorresponde(PendingConfirmation pending) {
        if (pending.getEstado() == EstadoConfirmacion.PENDIENTE && !pending.getExpira().isAfter(clock.instant()))
            pending.setEstado(EstadoConfirmacion.EXPIRADA);
    }

    private ConfirmacionResumen resumen(PendingConfirmation pending) {
        return new ConfirmacionResumen(pending.getId(), pending.getTipo(), pending.getEstado(),
                pending.getResumen(), mapper.readTree(pending.getParametros()), pending.getExpira(),
                pending.getResultado() == null ? null : mapper.readValue(pending.getResultado(), OperacionResumen.class));
    }

    private Usuario autorizado(TipoOperacion tipo) {
        Usuario usuario = usuarios.usuarioActual();
        exigirRol(usuario, tipo);
        return usuario;
    }

    private void exigirRol(Usuario usuario, TipoOperacion tipo) {
        Rol requerido = switch (tipo) {
            case CREAR_RUTA, CREAR_VIAJE, EDITAR_RUTA, ELIMINAR_RUTA, REPROGRAMAR_VIAJE,
                 ACEPTAR_SOLICITUD, RECHAZAR_SOLICITUD, RECHAZAR_SOLICITUDES_PENDIENTES,
                 CONFIRMAR_VIAJE, INICIAR_VIAJE, COMPLETAR_VIAJE, CANCELAR_VIAJE -> Rol.CONDUCTOR;
            case SOLICITAR_UNIRSE, CANCELAR_SOLICITUD -> Rol.PASAJERO;
            case REGISTRAR_METODO, EDITAR_PERFIL, EDITAR_INFO_ADICIONAL,
                 CAMBIAR_ROL, REGISTRAR_CURSO, ELIMINAR_CURSO -> null;
            case ACTUALIZAR_METODO, CAMBIAR_ESTADO_METODO, ELIMINAR_METODO -> {
                if (usuario.getRolPrincipal() == null) throw new NoPermitidoException("La operación requiere un rol activo.");
                yield null;
            }
        };
        if (requerido != null && usuario.getRolPrincipal() != requerido)
            throw new NoPermitidoException("La operación requiere el rol " + requerido + ".");
    }

    private void validar(Object request) {
        if (request == null) throw new CamposInvalidosException("Faltan los datos de la operación");
        var violations = validator.validate(request);
        if (!violations.isEmpty()) throw new CamposInvalidosException(violations.iterator().next().getMessage());
        String json = mapper.writeValueAsString(request);
        if (json.length() > 8000) throw new CamposInvalidosException("Los datos de la operación son demasiado extensos");
        AgentConversationStore.validateNoSecrets(json);
        if (request instanceof RutaRequest ruta && (ruta.origen().length() > 255
                || (ruta.notaContribucion() != null && ruta.notaContribucion().length() > 255)))
            throw new CamposInvalidosException("El origen y la nota de contribución no pueden superar 255 caracteres");
        if (request instanceof MetodoCompensacionRequest metodo && metodo.descripcion().length() > 255)
            throw new CamposInvalidosException("La descripción no puede superar 255 caracteres");
    }

    private JsonNode snapshot(Ruta ruta) {
        var data = mapper.createObjectNode();
        data.put("rutaId", ruta.getId());
        data.put("conductorId", ruta.getConductor().getId());
        data.put("origen", ruta.getOrigen()); data.put("destino", ruta.getDestino());
        data.put("capacidad", ruta.getCapacidadMaxima());
        data.put("referencias", ruta.getPuntosReferencia());
        data.put("zonas", ruta.getZonasSinParada()); data.put("nota", ruta.getNotaContribucion());
        data.put("estado", ruta.getEstado().name());
        return data;
    }

    private JsonNode snapshot(Viaje viaje) {
        var data = mapper.createObjectNode();
        data.set("ruta", snapshot(viaje.getRuta()));
        data.put("viajeId", viaje.getId()); data.put("fecha", viaje.getFecha().toString());
        data.put("hora", viaje.getHora().toString()); data.put("estado", viaje.getEstado().name());
        return data;
    }

    private JsonNode snapshotMetodo(Long id, Long usuarioId, TipoCompensacion tipo, String descripcion, boolean activo) {
        return mapper.createObjectNode().put("metodoId", id).put("usuarioId", usuarioId).put("tipo", tipo.name())
                .put("descripcion", descripcion).put("activo", activo);
    }

    private JsonNode snapshotPerfil(Usuario usuario, Perfil perfil) {
        var data = mapper.createObjectNode().put("usuarioId", usuario.getId());
        if (perfil == null) {
            return data.put("existe", false);
        }
        data.put("existe", true);
        data.put("perfilId", perfil.getId());
        data.put("nombre", perfil.getNombre());
        data.put("apellidos", perfil.getApellidos());
        data.put("carrera", perfil.getCarrera());
        data.put("distrito", perfil.getDistrito());
        data.put("universidad", perfil.getUniversidad());
        data.put("diasDisponibles", perfil.getDiasDisponibles());
        data.put("horariosDisponibles", perfil.getHorariosDisponibles());
        data.put("fechaInicioClases", Objects.toString(perfil.getFechaInicioClases(), null));
        data.put("fechaFinClases", Objects.toString(perfil.getFechaFinClases(), null));
        data.put("metodoCompensacionFavorito",
                perfil.getMetodoCompensacionFavorito() == null ? null : perfil.getMetodoCompensacionFavorito().name());
        return data;
    }

    private JsonNode snapshotInfoAdicional(Perfil perfil) {
        return mapper.createObjectNode()
                .put("perfilId", perfil.getId())
                .put("gustos", perfil.getGustos())
                .put("hobbies", perfil.getHobbies())
                .put("datosCuriosos", perfil.getDatosCuriosos());
    }

    private JsonNode snapshotRol(Usuario usuario) {
        return mapper.createObjectNode()
                .put("usuarioId", usuario.getId())
                .put("rol", usuario.getRolPrincipal() == null ? null : usuario.getRolPrincipal().name());
    }

    private JsonNode snapshotCurso(Curso curso) {
        return mapper.createObjectNode()
                .put("cursoId", curso.getId())
                .put("nombre", curso.getNombre())
                .put("dia", curso.getDia())
                .put("horaInicio", Objects.toString(curso.getHoraInicio(), null))
                .put("horaFin", Objects.toString(curso.getHoraFin(), null));
    }

    private String valorResultante(String valorNuevo, String valorActual) {
        String valor = valorNuevo != null ? valorNuevo : valorActual;
        return valor == null ? null : valor.trim();
    }

    private String normalizarDia(String dia) {
        String n = Normalizer.normalize(dia.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "");
    }

    private String describirMetodo(TipoCompensacion tipo, String descripcion, boolean activo) {
        return tipo + " «" + descripcion + "» (" + (activo ? "activo" : "inactivo") + ")";
    }

    private void comprobarSnapshot(PendingConfirmation pending, JsonNode actual) {
        // JsonNode numeric subtypes may differ before/after persistence (LongNode versus IntNode).
        // Parse both representations identically; values and all fields are still compared exactly.
        if (!mapper.readTree(pending.getSnapshot()).equals(mapper.readTree(actual.toString())))
            throw new BusinessException(SNAPSHOT_OBSOLETO);
    }

    private String opcional(String prefix, String value) {
        return value == null || value.isBlank() ? "" : prefix + value;
    }
}
