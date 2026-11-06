package com.uniride.services;

import com.uniride.dto.requests.ViajeRequest;
import com.uniride.dto.responses.ViajeRespuesta;
import com.uniride.entities.Penalidad;
import com.uniride.entities.Ruta;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.TipoNotificacion;
import com.uniride.enums.TipoPenalidad;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.ViajeMapper;
import com.uniride.repositories.PenalidadRepository;
import com.uniride.repositories.RutaRepository;
import com.uniride.repositories.SolicitudRepository;
import com.uniride.repositories.ViajeRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class ViajeService {

    @org.springframework.beans.factory.annotation.Autowired
    private java.time.Clock clock = java.time.Clock.system(com.uniride.config.TiempoConfig.LIMA);

    private static final Duration PLAZO_CONFIRMACION = Duration.ofHours(12);
    private static final Duration UMBRAL_RECORDATORIO = Duration.ofHours(20);
    private static final Duration UMBRAL_CANCELACION_SIN_PENALIDAD = Duration.ofHours(24);

    private final ViajeRepository viajeRepository;
    private final RutaRepository rutaRepository;
    private final SolicitudRepository solicitudRepository;
    private final PenalidadRepository penalidadRepository;
    private final ViajeMapper viajeMapper;
    private final UsuarioService usuarioService;
    private final NotificacionService notificacionService;

    public ViajeService(ViajeRepository viajeRepository, RutaRepository rutaRepository,
            SolicitudRepository solicitudRepository, PenalidadRepository penalidadRepository,
            ViajeMapper viajeMapper, UsuarioService usuarioService,
            NotificacionService notificacionService) {
        this.viajeRepository = viajeRepository;
        this.rutaRepository = rutaRepository;
        this.solicitudRepository = solicitudRepository;
        this.penalidadRepository = penalidadRepository;
        this.viajeMapper = viajeMapper;
        this.usuarioService = usuarioService;
        this.notificacionService = notificacionService;
    }

    @Transactional
    public ViajeRespuesta crear(Long rutaId, ViajeRequest request) {
        Usuario conductor = usuarioService.usuarioActual();
        Ruta ruta = buscarRutaPropia(rutaId, conductor);
        if (ruta.getEstado() == EstadoRuta.ELIMINADA) {
            throw new BusinessException("No se pueden crear viajes en una ruta eliminada");
        }

        validarNuevaSalida(request);

        Viaje viaje = viajeMapper.toViaje(request);
        viaje.setRuta(ruta);
        viaje.setDia(diaDeLaSemana(request.fecha()));
        viaje.setEstado(EstadoViaje.PROGRAMADO);
        Viaje guardado = viajeRepository.save(viaje);

        notificacionService.notificar(conductor, TipoNotificacion.EXITO, "Viaje creado",
                "Se creó el viaje del " + viaje.getDia() + " " + viaje.getFecha()
                        + " a las " + viaje.getHora() + " en tu ruta "
                        + ruta.getOrigen() + " → " + ruta.getDestino() + ".");

        return viajeMapper.toViajeRespuesta(guardado);
    }

    /** US07: reprogramar fecha/hora de un viaje sin borrar solicitudes ni historial. */
    @Transactional
    public ViajeRespuesta actualizar(Long id, ViajeRequest request, boolean confirmarImpacto) {
        Usuario conductor = usuarioService.usuarioActual();
        Viaje viaje = viajeRepository.bloquearParaConductor(id, conductor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));

        if (viaje.getRuta().getEstado() == EstadoRuta.ELIMINADA) {
            throw new BusinessException("No se puede reprogramar un viaje de una ruta eliminada");
        }
        if (viaje.getEstado() != EstadoViaje.PROGRAMADO) {
            throw new BusinessException("Solo se pueden reprogramar viajes en estado PROGRAMADO");
        }
        if (request == null || request.fecha() == null || request.hora() == null) {
            throw new CamposInvalidosException("La fecha y la hora son obligatorias");
        }
        LocalDateTime nuevaSalida = LocalDateTime.of(request.fecha(), request.hora());
        LocalDateTime ahora = LocalDateTime.now(clock);
        if (!nuevaSalida.isAfter(ahora)) {
            throw new CamposInvalidosException("La nueva fecha y hora deben ser futuras");
        }
        if (viaje.getFecha().equals(request.fecha()) && viaje.getHora().equals(request.hora())) {
            return viajeMapper.toViajeRespuesta(viaje, "El horario no cambió");
        }
        // Un viaje confirmado deberá confirmarse nuevamente según US17.
        // Nunca permitir dejarlo sin posibilidad de confirmar 12 h antes.
        validarNuevaSalida(request);

        List<Solicitud> aceptadas = solicitudRepository.findByViajeIdAndEstadoIn(
                id, List.of(EstadoSolicitud.ACEPTADA));
        if (!aceptadas.isEmpty() && !confirmarImpacto) {
            throw new BusinessException("ADVERTENCIA: el cambio de horario afecta a "
                    + aceptadas.size() + " pasajero(s) aceptado(s). "
                    + "Confirma usando ?confirmarImpacto=true.");
        }

        viaje.setFecha(request.fecha());
        viaje.setHora(request.hora());
        viaje.setDia(diaDeLaSemana(request.fecha()));
        if (viaje.isConfirmado()) {
            viaje.setConfirmado(false);
            viaje.setFechaConfirmacion(null);
        }
        viaje.setRecordatorioEnviado(false);
        Viaje actualizado = viajeRepository.save(viaje);

        for (Solicitud solicitud : aceptadas) {
            notificacionService.notificar(solicitud.getPasajero(), TipoNotificacion.ADVERTENCIA,
                    "Horario de viaje modificado",
                    "El conductor reprogramó tu viaje para el " + actualizado.getFecha()
                            + " a las " + actualizado.getHora()
                            + ". Revisa el nuevo horario en UniRide.");
        }
        notificacionService.notificar(conductor, TipoNotificacion.EXITO,
                "Viaje reprogramado", "Actualizaste la salida del viaje al "
                        + actualizado.getFecha() + " a las " + actualizado.getHora()
                        + ". Si estaba confirmado, debes confirmarlo nuevamente.");
        return viajeMapper.toViajeRespuesta(actualizado, "Horario del viaje actualizado");
    }

    @Transactional
    public Page<ViajeRespuesta> listarPorRuta(Long rutaId, int page, int size) {
        Usuario conductor = usuarioService.usuarioActual();
        buscarRutaPropia(rutaId, conductor);

        return viajeRepository
                .findByRutaIdOrderByFechaAscHoraAsc(
                        rutaId,
                        PageRequest.of(page, size))
                .map(viajeMapper::toViajeRespuesta);
    }

    /**
     * Cierre transversal: la eliminacion fisica solo corresponde a viajes sin actividad.
     * Conservar solicitudes, acuerdos, penalidades e historial en los demas casos.
     * Las cancelaciones de viajes con participantes mantienen el flujo US18.
     */
    @Transactional
    public void eliminar(Long id) {
        Usuario conductor = usuarioService.usuarioActual();
        Viaje viaje = viajeRepository.bloquearParaConductor(id, conductor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));

        if (viaje.getEstado() != EstadoViaje.PROGRAMADO || viaje.isConfirmado()) {
            throw new BusinessException("No se puede eliminar un viaje confirmado o historico. "
                    + "El historial se conserva; si corresponde, usa la cancelacion del viaje.");
        }
        if (solicitudRepository.existsByViajeId(id)) {
            throw new BusinessException("No se puede eliminar un viaje con solicitudes registradas. "
                    + "Cancela el viaje mediante PUT /viajes/" + id + "/cancelar "
                    + "para conservar las solicitudes y avisar a los involucrados.");
        }
        if (penalidadRepository.existsByViajeId(id)) {
            throw new BusinessException("No se puede eliminar un viaje con penalidades asociadas. "
                    + "El historial de sanciones debe conservarse.");
        }

        viajeRepository.delete(viaje);
        // Detectar errores de integridad antes de registrar el aviso de exito.
        viajeRepository.flush();

        notificacionService.notificar(conductor, TipoNotificacion.EXITO, "Viaje eliminado",
                "Se eliminó el viaje del " + viaje.getDia() + " " + viaje.getFecha()
                        + " a las " + viaje.getHora() + ".");
    }

    @Transactional
    public ViajeRespuesta confirmar(Long id) {
        Usuario conductor = usuarioService.usuarioActual();
        Viaje viaje = viajeRepository.bloquearParaConductor(id, conductor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));

        if (viaje.isConfirmado()) {
            notificacionService.notificar(conductor, TipoNotificacion.EXITO,
                    "Viaje ya confirmado",
                    "El viaje del " + viaje.getDia() + " " + viaje.getFecha()
                            + " a las " + viaje.getHora() + " ya estaba confirmado previamente.");
            return viajeMapper.toViajeRespuesta(viaje, "El viaje ya estaba confirmado");
        }
        if (viaje.getEstado() != EstadoViaje.PROGRAMADO) {
            throw new BusinessException(
                    "El viaje no puede confirmarse; estado actual: " + viaje.getEstado());
        }

        Duration hastaSalida = Duration.between(LocalDateTime.now(clock), horaSalida(viaje));
        if (hastaSalida.compareTo(PLAZO_CONFIRMACION) < 0) {
            vencer(viaje);
            return viajeMapper.toViajeRespuesta(viaje,
                    "El plazo de confirmación de 12 horas venció; el viaje quedó VENCIDO");
        }

        viaje.setConfirmado(true);
        viaje.setFechaConfirmacion(LocalDateTime.now(clock));
        Viaje guardado = viajeRepository.save(viaje);

        List<Solicitud> aceptadas = solicitudRepository.findByViajeIdAndEstadoIn(
                id, List.of(EstadoSolicitud.ACEPTADA));
        for (Solicitud solicitud : aceptadas) {
            notificacionService.notificar(solicitud.getPasajero(), TipoNotificacion.EXITO,
                    "Viaje confirmado",
                    "Tu viaje del " + viaje.getDia() + " " + viaje.getFecha()
                            + " a las " + viaje.getHora()
                            + " (" + viaje.getRuta().getOrigen() + " → " + viaje.getRuta().getDestino()
                            + ") fue confirmado por el conductor.");
        }
        notificacionService.notificar(conductor, TipoNotificacion.EXITO, "Viaje confirmado",
                "Confirmaste el viaje del " + viaje.getDia() + " " + viaje.getFecha()
                        + " a las " + viaje.getHora() + ".");

        return viajeMapper.toViajeRespuesta(guardado,
                "Viaje confirmado; se notificó a los pasajeros aceptados");
    }

    @Transactional
    public ViajeRespuesta iniciar(Long id) {
        Usuario conductor = usuarioService.usuarioActual();
        Viaje viaje = viajeRepository.bloquearParaConductor(id, conductor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));

        if (viaje.getEstado() == EstadoViaje.EN_PROGRESO) {
            notificacionService.notificar(conductor, TipoNotificacion.EXITO,
                    "Viaje ya iniciado",
                    "El viaje del " + viaje.getDia() + " " + viaje.getFecha()
                            + " a las " + viaje.getHora() + " ya estaba en progreso.");
            return viajeMapper.toViajeRespuesta(viaje, "El viaje ya estaba iniciado");
        }
        if (viaje.getEstado() == EstadoViaje.COMPLETADO) {
            notificacionService.notificar(conductor, TipoNotificacion.EXITO,
                    "Viaje ya completado",
                    "El viaje del " + viaje.getDia() + " " + viaje.getFecha()
                            + " a las " + viaje.getHora() + " ya había sido completado.");
            return viajeMapper.toViajeRespuesta(viaje, "El viaje ya había sido completado");
        }
        if (viaje.getEstado() != EstadoViaje.PROGRAMADO) {
            throw new BusinessException(
                    "El viaje no puede iniciarse; estado actual: " + viaje.getEstado());
        }
        if (!viaje.isConfirmado()) {
            throw new BusinessException("El viaje debe estar confirmado para poder iniciarlo");
        }

        if (LocalDateTime.now(clock).isBefore(horaSalida(viaje))) {
            throw new BusinessException("El viaje no puede iniciarse antes de su hora programada");
        }
        viaje.setEstado(EstadoViaje.EN_PROGRESO);
        Viaje iniciado = viajeRepository.save(viaje);

        notificacionService.notificar(conductor, TipoNotificacion.EXITO, "Viaje iniciado",
                "Iniciaste el viaje del " + viaje.getDia() + " " + viaje.getFecha()
                        + " a las " + viaje.getHora() + ".");
        for (Solicitud solicitud : solicitudRepository.findByViajeIdAndEstadoIn(
                id, List.of(EstadoSolicitud.ACEPTADA))) {
            notificacionService.notificar(solicitud.getPasajero(), TipoNotificacion.EXITO,
                    "Tu viaje inició",
                    "El conductor inició el viaje del " + viaje.getDia() + " " + viaje.getFecha()
                            + " a las " + viaje.getHora()
                            + " (" + viaje.getRuta().getOrigen() + " → " + viaje.getRuta().getDestino() + ").");
        }

        return viajeMapper.toViajeRespuesta(iniciado, "Viaje iniciado");
    }

    @Transactional
    public ViajeRespuesta completar(Long id) {
        Usuario conductor = usuarioService.usuarioActual();
        Viaje viaje = viajeRepository.bloquearParaConductor(id, conductor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));

        if (viaje.getEstado() == EstadoViaje.COMPLETADO) {
            notificacionService.notificar(conductor, TipoNotificacion.EXITO,
                    "Viaje ya completado",
                    "El viaje del " + viaje.getDia() + " " + viaje.getFecha()
                            + " a las " + viaje.getHora() + " ya estaba completado.");
            return viajeMapper.toViajeRespuesta(viaje, "El viaje ya estaba completado");
        }
        if (viaje.getEstado() != EstadoViaje.EN_PROGRESO) {
            throw new BusinessException(
                    "El viaje solo puede completarse desde EN_PROGRESO; estado actual: "
                            + viaje.getEstado());
        }

        viaje.setEstado(EstadoViaje.COMPLETADO);
        Viaje completado = viajeRepository.save(viaje);

        notificacionService.notificar(conductor, TipoNotificacion.EXITO, "Viaje completado",
                "Completaste el viaje del " + viaje.getDia() + " " + viaje.getFecha()
                        + " a las " + viaje.getHora() + ".");
        for (Solicitud solicitud : solicitudRepository.findByViajeIdAndEstadoIn(
                id, List.of(EstadoSolicitud.ACEPTADA))) {
            notificacionService.notificar(solicitud.getPasajero(), TipoNotificacion.EXITO,
                    "Viaje completado",
                    "El viaje del " + viaje.getDia() + " " + viaje.getFecha()
                            + " a las " + viaje.getHora()
                            + " (" + viaje.getRuta().getOrigen() + " → " + viaje.getRuta().getDestino()
                            + ") fue completado. ¡Gracias por viajar con UniRide!");
        }

        return viajeMapper.toViajeRespuesta(completado, "Viaje completado");
    }

    @Transactional
    public ViajeRespuesta cancelar(Long id) {
        Usuario usuario = usuarioService.usuarioActual();
        // Bloquear la misma fila que usa la aceptacion: evita carreras entre
        // aceptar una solicitud, cancelar un viaje e iniciar un viaje.
        Viaje viaje = viajeRepository.bloquearParaAceptar(id)
                .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));

        boolean esConductor = viaje.getRuta().getConductor().getId().equals(usuario.getId());
        boolean esAutor = viaje.getCanceladoPor() != null
                && viaje.getCanceladoPor().getId().equals(usuario.getId());
        if (!esConductor && !esAutor) {
            boolean aceptada = solicitudRepository.existsByViajeIdAndPasajeroIdAndEstadoIn(
                    id, usuario.getId(), List.of(EstadoSolicitud.ACEPTADA));
            if (!aceptada) {
                throw new NoPermitidoException(
                        "Solo el conductor o un pasajero con solicitud aceptada puede cancelar el viaje");
            }
        }

        if (viaje.getEstado() == EstadoViaje.CANCELADO) {
            return viajeMapper.toViajeRespuesta(viaje, "El viaje ya estaba cancelado");
        }
        if (viaje.getEstado() != EstadoViaje.PROGRAMADO) {
            throw new BusinessException(
                    "El viaje no puede cancelarse; estado actual: " + viaje.getEstado());
        }

        Duration hastaSalida = Duration.between(LocalDateTime.now(clock), horaSalida(viaje));
        if (hastaSalida.isNegative()) {
            throw new BusinessException("El viaje ya ocurrió");
        }
        TipoPenalidad penalidad = clasificarPenalidad(hastaSalida);

        viaje.setCanceladoPor(usuario);
        viaje.setFechaCancelacion(LocalDateTime.now(clock));
        viaje.setEstado(EstadoViaje.CANCELADO);
        Viaje cancelado = viajeRepository.save(viaje);

        if (penalidad != null) {
            penalidadRepository.save(Penalidad.builder()
                    .usuario(usuario)
                    .viaje(viaje)
                    .tipo(penalidad)
                    .motivo("Canceló el viaje con " + hastaSalida.toHours()
                            + " horas de anticipación")
                    .build());
        }

        List<Solicitud> involucradas = solicitudRepository.findByViajeIdAndEstadoIn(
                id, List.of(EstadoSolicitud.PENDIENTE, EstadoSolicitud.ACEPTADA));
        for (Solicitud solicitud : involucradas) {
            solicitud.setEstado(EstadoSolicitud.CANCELADA);
        }
        solicitudRepository.saveAll(involucradas);
        viaje.setPasajeros(0);

        Set<Usuario> aNotificar = new LinkedHashSet<>();
        aNotificar.add(viaje.getRuta().getConductor());
        for (Solicitud solicitud : involucradas) {
            aNotificar.add(solicitud.getPasajero());
        }
        aNotificar.removeIf(destinatario -> destinatario.getId().equals(usuario.getId()));

        TipoNotificacion tipoNotificacion =
                hastaSalida.compareTo(Duration.ofHours(4)) < 0
                        ? TipoNotificacion.ERROR
                        : TipoNotificacion.ADVERTENCIA;
        String urgencia = hastaSalida.compareTo(Duration.ofHours(4)) < 0
                ? " (menos de 4 horas antes de la salida)"
                : "";
        String mensaje = "El viaje del " + viaje.getDia() + " " + viaje.getFecha()
                + " a las " + viaje.getHora()
                + " (" + viaje.getRuta().getOrigen() + " → " + viaje.getRuta().getDestino()
                + ") fue cancelado por " + usuario.getNombre() + urgencia + ".";
        for (Usuario destinatario : aNotificar) {
            notificacionService.notificar(destinatario, tipoNotificacion,
                    "Viaje cancelado", mensaje);
        }

        String mensajeActor;
        if (penalidad == null) {
            notificacionService.notificar(usuario, TipoNotificacion.EXITO, "Viaje cancelado",
                    "Cancelaste el viaje con más de 24 horas de anticipación; no se aplica penalidad.");
            mensajeActor = "Viaje cancelado sin penalidad (más de 24 horas de anticipación)";
        } else {
            notificacionService.notificar(usuario, TipoNotificacion.ADVERTENCIA,
                    "Penalidad registrada",
                    "Cancelaste el viaje con " + hastaSalida.toHours()
                            + " horas de anticipación; se registró una penalidad "
                            + (penalidad == TipoPenalidad.GRAVE ? "grave" : "leve") + " en tu perfil.");
            mensajeActor = "Viaje cancelado; penalidad "
                    + (penalidad == TipoPenalidad.GRAVE ? "GRAVE" : "LEVE") + " registrada";
        }

        return viajeMapper.toViajeRespuesta(cancelado, mensajeActor);
    }

    @Scheduled(fixedRate = 300000)
    @Transactional
    public void enviarRecordatoriosAutomaticos() {

        LocalDate hoy =
                LocalDate.now(clock);

        LocalDateTime ahora =
                LocalDateTime.now(clock);

        List<Viaje> candidatos =
                viajeRepository.buscarPendientesDeConfirmacionAutomaticos(
                        EstadoViaje.PROGRAMADO,
                        hoy,
                        hoy.plusDays(2));

        for (Viaje viaje : candidatos) {

            // Defensa adicional: la consulta ya filtra esta marca, pero un mismo
            // objeto puede ser reusado en invocaciones de prueba o repetidas.
            if (viaje.isRecordatorioEnviado() || viaje.isConfirmado()
                    || viaje.getEstado() != EstadoViaje.PROGRAMADO) {
                continue;
            }

            Duration faltan =
                    Duration.between(
                            ahora,
                            horaSalida(viaje));

            if (faltan.isNegative()
                    || faltan.compareTo(UMBRAL_RECORDATORIO) > 0
                    || faltan.compareTo(PLAZO_CONFIRMACION) < 0) {
                continue;
            }

            Usuario conductor =
                    viaje.getRuta().getConductor();

            viaje.setRecordatorioEnviado(true);
            viajeRepository.save(viaje);

            notificacionService.notificar(
                    conductor,
                    TipoNotificacion.RECORDATORIO,
                    "Recordatorio: confirma tu viaje",
                    "Faltan menos de 20 horas para tu viaje del "
                            + viaje.getDia()
                            + " "
                            + viaje.getFecha()
                            + " a las "
                            + viaje.getHora()
                            + ". Confírmalo antes de que falten 12 horas "
                            + "para evitar que el viaje venza.");
        }
    }

    @Scheduled(fixedDelayString = "${uniride.viajes.vencimiento-ms:60000}")
    @Transactional
    public void vencerAutomaticamente() {
        LocalDateTime limite = LocalDateTime.now(clock).plus(PLAZO_CONFIRMACION);
        for (Viaje viaje : viajeRepository.bloquearVencidos(
                EstadoViaje.PROGRAMADO, limite.toLocalDate(), limite.toLocalTime())) {
            vencer(viaje);
        }
    }

    private void vencer(Viaje viaje) {
        if (viaje.getEstado() != EstadoViaje.PROGRAMADO || viaje.isConfirmado()) return;
        viaje.setEstado(EstadoViaje.VENCIDO);
        viajeRepository.save(viaje);
        penalidadRepository.save(Penalidad.builder().usuario(viaje.getRuta().getConductor())
                .viaje(viaje).tipo(TipoPenalidad.LEVE)
                .motivo("No confirmó el viaje con al menos 12 horas de anticipación").build());
        notificacionService.notificar(viaje.getRuta().getConductor(), TipoNotificacion.ERROR,
                "Confirmación vencida", "El viaje " + viaje.getId()
                        + " quedó VENCIDO y se registró una penalidad leve.");
        // Las solicitudes y acuerdos permanecen como historial, sin borrar ni reasignar cupos.
        for (Solicitud solicitud : solicitudRepository.findByViajeIdAndEstadoIn(
                viaje.getId(), List.of(EstadoSolicitud.PENDIENTE, EstadoSolicitud.ACEPTADA))) {
            notificacionService.notificar(solicitud.getPasajero(), TipoNotificacion.ADVERTENCIA,
                    "Viaje vencido", "El viaje " + viaje.getId() + " venció por falta de confirmación.");
        }
    }

    private void validarNuevaSalida(ViajeRequest request) {
        if (request == null || request.fecha() == null || request.hora() == null) {
            throw new CamposInvalidosException("La fecha y la hora son obligatorias");
        }
        LocalDateTime salida = LocalDateTime.of(request.fecha(), request.hora());
        if (!salida.isAfter(LocalDateTime.now(clock))) {
            throw new CamposInvalidosException("La fecha y hora del viaje deben ser futuras");
        }
        if (salida.isBefore(LocalDateTime.now(clock).plus(PLAZO_CONFIRMACION))) {
            throw new BusinessException("La salida debe tener al menos 12 horas de anticipación");
        }
    }

    private TipoPenalidad clasificarPenalidad(Duration hastaSalida) {
        if (hastaSalida.compareTo(UMBRAL_CANCELACION_SIN_PENALIDAD) > 0) {
            return null;
        }

        if (hastaSalida.compareTo(Duration.ofHours(4)) < 0) {
            return TipoPenalidad.GRAVE;
        }

        return TipoPenalidad.LEVE;
    }

    private LocalDateTime horaSalida(Viaje viaje) {
        return LocalDateTime.of(viaje.getFecha(), viaje.getHora());
    }

    private Ruta buscarRutaPropia(Long rutaId, Usuario conductor) {
        return rutaRepository.findByIdAndConductorId(rutaId, conductor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Ruta no encontrada"));
    }

    private String diaDeLaSemana(LocalDate fecha) {
        return switch (fecha.getDayOfWeek()) {
            case MONDAY -> "Lunes";
            case TUESDAY -> "Martes";
            case WEDNESDAY -> "Miercoles";
            case THURSDAY -> "Jueves";
            case FRIDAY -> "Viernes";
            case SATURDAY -> "Sabado";
            case SUNDAY -> "Domingo";
        };
    }
}
