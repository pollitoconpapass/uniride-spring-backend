package com.uniride.services;

import com.uniride.dto.ViajeRequest;
import com.uniride.dto.ViajeRespuesta;
import com.uniride.entities.Penalidad;
import com.uniride.entities.Ruta;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ViajeService {

    private static final Duration PLAZO_CONFIRMACION = Duration.ofHours(24);
    private static final Duration UMBRAL_RECORDATORIO = Duration.ofHours(20);

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

        if (request.getFecha().isBefore(LocalDate.now())) {
            throw new CamposInvalidosException("La fecha del viaje no puede ser en el pasado");
        }

        Viaje viaje = viajeMapper.toViaje(request);
        viaje.setRuta(ruta);
        viaje.setDia(diaDeLaSemana(request.getFecha()));
        viaje.setEstado(EstadoViaje.PROGRAMADO);
        Viaje guardado = viajeRepository.save(viaje);

        notificacionService.notificar(conductor, TipoNotificacion.EXITO, "Viaje creado",
                "Se creó el viaje del " + viaje.getDia() + " " + viaje.getFecha()
                        + " a las " + viaje.getHora() + " en tu ruta "
                        + ruta.getOrigen() + " → " + ruta.getDestino() + ".");

        return viajeMapper.toViajeRespuesta(guardado);
    }

    @Transactional
    public Page<ViajeRespuesta> listarPorRuta(Long rutaId, int page, int size) {
        Usuario conductor = usuarioService.usuarioActual();
        buscarRutaPropia(rutaId, conductor);
        enviarRecordatorios(conductor);
        return viajeRepository.findByRutaIdOrderByFechaAscHoraAsc(rutaId, PageRequest.of(page, size))
                .map(viajeMapper::toViajeRespuesta);
    }

    @Transactional
    public void eliminar(Long id) {
        Usuario conductor = usuarioService.usuarioActual();
        Viaje viaje = viajeRepository.findByIdAndRutaConductorId(id, conductor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));

        viajeRepository.delete(viaje);

        notificacionService.notificar(conductor, TipoNotificacion.EXITO, "Viaje eliminado",
                "Se eliminó el viaje del " + viaje.getDia() + " " + viaje.getFecha()
                        + " a las " + viaje.getHora() + ".");
    }

    @Transactional
    public ViajeRespuesta confirmar(Long id) {
        Usuario conductor = usuarioService.usuarioActual();
        Viaje viaje = viajeRepository.findByIdAndRutaConductorId(id, conductor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));

        if (viaje.isConfirmado()) {
            notificacionService.notificar(conductor, TipoNotificacion.EXITO,
                    "Viaje ya confirmado",
                    "El viaje del " + viaje.getDia() + " " + viaje.getFecha()
                            + " a las " + viaje.getHora() + " ya estaba confirmado previamente.");
            ViajeRespuesta respuesta = viajeMapper.toViajeRespuesta(viaje);
            respuesta.setMensaje("El viaje ya estaba confirmado");
            return respuesta;
        }
        if (viaje.getEstado() != EstadoViaje.PROGRAMADO) {
            throw new BusinessException(
                    "El viaje no puede confirmarse; estado actual: " + viaje.getEstado());
        }

        Duration hastaSalida = Duration.between(LocalDateTime.now(), horaSalida(viaje));
        if (hastaSalida.isNegative()) {
            throw new BusinessException("El viaje ya ocurrió");
        }
        if (hastaSalida.compareTo(PLAZO_CONFIRMACION) < 0) {
            viaje.setEstado(EstadoViaje.VENCIDO);
            viajeRepository.save(viaje);
            penalidadRepository.save(Penalidad.builder()
                    .usuario(conductor)
                    .viaje(viaje)
                    .tipo(TipoPenalidad.LEVE)
                    .motivo("No confirmó el viaje dentro del plazo de 24 horas antes de la salida")
                    .build());
            notificacionService.notificar(conductor, TipoNotificacion.ERROR,
                    "Confirmación vencida",
                    "No confirmaste a tiempo el viaje del " + viaje.getDia() + " " + viaje.getFecha()
                            + " a las " + viaje.getHora()
                            + ". El viaje quedó VENCIDO y se registró una penalidad leve en tu perfil.");
            ViajeRespuesta respuesta = viajeMapper.toViajeRespuesta(viaje);
            respuesta.setMensaje("El plazo de confirmación (24 horas antes) venció; "
                    + "el viaje quedó VENCIDO y se registró una penalidad leve");
            return respuesta;
        }

        viaje.setConfirmado(true);
        viaje.setFechaConfirmacion(LocalDateTime.now());
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

        ViajeRespuesta respuesta = viajeMapper.toViajeRespuesta(guardado);
        respuesta.setMensaje("Viaje confirmado; se notificó a los pasajeros aceptados");
        return respuesta;
    }

    @Transactional
    public ViajeRespuesta cancelar(Long id) {
        Usuario usuario = usuarioService.usuarioActual();
        Viaje viaje = viajeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));

        boolean esConductor = viaje.getRuta().getConductor().getId().equals(usuario.getId());
        if (!esConductor) {
            boolean aceptada = solicitudRepository.existsByViajeIdAndPasajeroIdAndEstadoIn(
                    id, usuario.getId(), List.of(EstadoSolicitud.ACEPTADA));
            if (!aceptada) {
                throw new NoPermitidoException(
                        "Solo el conductor o un pasajero con solicitud aceptada puede cancelar el viaje");
            }
        }

        if (viaje.getEstado() == EstadoViaje.CANCELADO) {
            notificacionService.notificar(usuario, TipoNotificacion.EXITO,
                    "Viaje ya cancelado",
                    "El viaje del " + viaje.getDia() + " " + viaje.getFecha()
                            + " a las " + viaje.getHora() + " ya estaba cancelado.");
            ViajeRespuesta respuesta = viajeMapper.toViajeRespuesta(viaje);
            respuesta.setMensaje("El viaje ya estaba cancelado");
            return respuesta;
        }
        if (viaje.getEstado() != EstadoViaje.PROGRAMADO) {
            throw new BusinessException(
                    "El viaje no puede cancelarse; estado actual: " + viaje.getEstado());
        }

        Duration hastaSalida = Duration.between(LocalDateTime.now(), horaSalida(viaje));
        if (hastaSalida.isNegative()) {
            throw new BusinessException("El viaje ya ocurrió");
        }
        TipoPenalidad penalidad = clasificarPenalidad(hastaSalida);

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

        ViajeRespuesta respuesta = viajeMapper.toViajeRespuesta(cancelado);
        respuesta.setMensaje(mensajeActor);
        return respuesta;
    }

    private void enviarRecordatorios(Usuario conductor) {
        LocalDate hoy = LocalDate.now();
        LocalDateTime ahora = LocalDateTime.now();
        List<Viaje> candidatos = viajeRepository.buscarPendientesDeConfirmacion(
                conductor.getId(), EstadoViaje.PROGRAMADO, hoy, hoy.plusDays(2));

        for (Viaje viaje : candidatos) {
            if (viaje.isRecordatorioEnviado()) {
                continue;
            }
            Duration faltan = Duration.between(ahora, horaSalida(viaje));
            if (faltan.isNegative() || faltan.compareTo(UMBRAL_RECORDATORIO) > 0) {
                continue;
            }
            viaje.setRecordatorioEnviado(true);
            viajeRepository.save(viaje);
            notificacionService.notificar(conductor, TipoNotificacion.RECORDATORIO,
                    "Recordatorio: confirma tu viaje",
                    "Faltan menos de 20 horas para tu viaje del " + viaje.getDia() + " "
                            + viaje.getFecha() + " a las " + viaje.getHora()
                            + ". Confírmalo a tiempo para evitar penalidades.");
        }
    }

    private TipoPenalidad clasificarPenalidad(Duration hastaSalida) {
        if (hastaSalida.compareTo(PLAZO_CONFIRMACION) > 0) {
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
