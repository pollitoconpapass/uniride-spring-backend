package com.uniride.services;

import com.uniride.dto.requests.RechazarMultipleRequest;
import com.uniride.dto.requests.RechazarRequest;
import com.uniride.dto.requests.SolicitudRequest;
import com.uniride.dto.responses.SolicitudRespuesta;
import com.uniride.entities.AcuerdoCompensacion;
import com.uniride.entities.MetodoCompensacion;
import com.uniride.entities.Perfil;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoAcuerdo;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.TipoNotificacion;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.SolicitudMapper;
import com.uniride.repositories.AcuerdoCompensacionRepository;
import com.uniride.repositories.MetodoCompensacionRepository;
import com.uniride.repositories.PerfilRepository;
import com.uniride.repositories.SolicitudRepository;
import com.uniride.repositories.ViajeRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SolicitudService {

    private static final List<EstadoSolicitud> ESTADOS_BLOQUEANTES =
            List.of(EstadoSolicitud.PENDIENTE, EstadoSolicitud.ACEPTADA);

    private final SolicitudRepository solicitudRepository;
    private final ViajeRepository viajeRepository;
    private final PerfilRepository perfilRepository;
    private final AcuerdoCompensacionRepository acuerdoCompensacionRepository;
    private final MetodoCompensacionRepository metodoCompensacionRepository;
    private final SolicitudMapper solicitudMapper;
    private final UsuarioService usuarioService;
    private final NotificacionService notificacionService;

    public SolicitudService(SolicitudRepository solicitudRepository, ViajeRepository viajeRepository,
            PerfilRepository perfilRepository, AcuerdoCompensacionRepository acuerdoCompensacionRepository,
            MetodoCompensacionRepository metodoCompensacionRepository,
            SolicitudMapper solicitudMapper, UsuarioService usuarioService,
            NotificacionService notificacionService) {
        this.solicitudRepository = solicitudRepository;
        this.viajeRepository = viajeRepository;
        this.perfilRepository = perfilRepository;
        this.acuerdoCompensacionRepository = acuerdoCompensacionRepository;
        this.metodoCompensacionRepository = metodoCompensacionRepository;
        this.solicitudMapper = solicitudMapper;
        this.usuarioService = usuarioService;
        this.notificacionService = notificacionService;
    }

    @Transactional
    public SolicitudRespuesta crear(Long viajeId, SolicitudRequest request) {
        Usuario pasajero = usuarioService.usuarioActual();
        Viaje viaje = viajeRepository.findById(viajeId)
                .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));

        Usuario conductor = viaje.getRuta().getConductor();
        if (conductor.getId().equals(pasajero.getId())) {
            throw new BusinessException("No puedes solicitar un viaje de tu propia ruta");
        }
        if (viaje.getFecha().isBefore(LocalDate.now(ZoneId.systemDefault()))) {
            throw new BusinessException("El viaje ya ocurrió");
        }
        if (viaje.getEstado() != EstadoViaje.PROGRAMADO) {
            throw new BusinessException(
                    "El viaje ya no acepta solicitudes; estado actual: " + viaje.getEstado());
        }
        if (solicitudRepository.existsByViajeIdAndPasajeroIdAndEstadoIn(
                viajeId, pasajero.getId(), ESTADOS_BLOQUEANTES)) {
            throw new BusinessException("Ya enviaste una solicitud para este viaje");
        }

        long aceptadas = solicitudRepository.countByViajeIdAndEstado(viajeId, EstadoSolicitud.ACEPTADA);
        if (aceptadas >= viaje.getRuta().getCapacidadMaxima()) {
            throw new BusinessException("La ruta ya no tiene cupos disponibles");
        }

        MetodoCompensacion metodo = validarMetodoCompensacion(request, pasajero);

        Solicitud solicitud = solicitudMapper.toSolicitud(request);
        solicitud.setViaje(viaje);
        solicitud.setPasajero(pasajero);
        solicitud.setEstado(EstadoSolicitud.PENDIENTE);
        solicitud.setMetodoCompensacion(metodo);
        Solicitud guardada = solicitudRepository.save(solicitud);

        notificacionService.notificar(conductor, TipoNotificacion.EXITO, "Nueva solicitud de viaje",
                pasajero.getNombre() + " solicitó unirse a tu viaje del "
                        + viaje.getDia() + " " + viaje.getFecha() + " a las " + viaje.getHora() + ".");

        return solicitudMapper.toSolicitudRespuesta(guardada,
                advertenciaPreferencia(pasajero, conductor, request), null, null);
    }

    @Transactional(readOnly = true)
    public Page<SolicitudRespuesta> listarMisSolicitudes(EstadoSolicitud estado, int page, int size) {
        Usuario pasajero = usuarioService.usuarioActual();
        PageRequest pageable = PageRequest.of(page, size, Sort.by("fechaCreacion").descending());

        Page<Solicitud> solicitudes = estado == null
                ? solicitudRepository.findByPasajeroId(pasajero.getId(), pageable)
                : solicitudRepository.buscarPorPasajeroYEstado(pasajero.getId(), estado, pageable);

        return solicitudes.map(solicitudMapper::toSolicitudRespuesta);
    }

    @Transactional
    public SolicitudRespuesta cancelar(Long id) {
        Usuario pasajero = usuarioService.usuarioActual();
        Solicitud solicitud = solicitudRepository.findByIdAndPasajeroId(id, pasajero.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Solicitud no encontrada"));

        if (solicitud.getEstado() != EstadoSolicitud.PENDIENTE) {
            throw new BusinessException(mensajeEstadoActual(solicitud));
        }
        if (solicitud.getViaje().getEstado() != EstadoViaje.PROGRAMADO) {
            throw new BusinessException(
                    "El viaje ya no está activo; estado actual: "
                            + solicitud.getViaje().getEstado());
        }
        if (solicitud.getViaje().getFecha().isBefore(LocalDate.now(ZoneId.systemDefault()))) {
            throw new BusinessException("El viaje ya ocurrió; no puedes cancelar la solicitud");
        }

        solicitud.setEstado(EstadoSolicitud.CANCELADA);
        Solicitud cancelada = solicitudRepository.save(solicitud);

        notificacionService.notificar(solicitud.getViaje().getRuta().getConductor(),
                TipoNotificacion.EXITO, "Solicitud cancelada",
                pasajero.getNombre() + " canceló su solicitud a tu viaje del "
                        + solicitud.getViaje().getDia() + " " + solicitud.getViaje().getFecha() + ".");

        return solicitudMapper.toSolicitudRespuesta(cancelada);
    }

    @Transactional(readOnly = true)
    public Page<SolicitudRespuesta> listarRecibidas(Long viajeId, EstadoSolicitud estado,
            int page, int size) {
        Usuario conductor = usuarioService.usuarioActual();
        viajeRepository.findByIdAndRutaConductorId(viajeId, conductor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado"));

        PageRequest pageable = PageRequest.of(page, size, Sort.by("fechaCreacion").descending());
        Page<Solicitud> solicitudes = estado == null
                ? solicitudRepository.findByViajeIdOrderByFechaCreacionDesc(viajeId, pageable)
                : solicitudRepository.buscarPorViajeYEstado(viajeId, estado, pageable);

        return solicitudes.map(solicitud -> {
            Perfil perfil = perfilRepository.findByUsuarioId(solicitud.getPasajero().getId())
                    .orElse(null);
            return solicitudMapper.toSolicitudRespuesta(solicitud, null,
                    perfil == null ? null : perfil.getCarrera(),
                    perfil == null ? null : perfil.getDistrito());
        });
    }

    @Transactional
    public SolicitudRespuesta aceptar(Long id) {
        Usuario conductor = usuarioService.usuarioActual();
        Solicitud solicitud = solicitudRepository.findByIdAndViajeRutaConductorId(id, conductor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Solicitud no encontrada"));
        validarSolicitudProcesable(solicitud);

        Viaje viaje = solicitud.getViaje();
        long aceptadas = solicitudRepository.countByViajeIdAndEstado(
                viaje.getId(), EstadoSolicitud.ACEPTADA);
        if (aceptadas >= viaje.getRuta().getCapacidadMaxima()) {
            throw new BusinessException("La ruta ya no tiene cupos disponibles");
        }

        solicitud.setEstado(EstadoSolicitud.ACEPTADA);
        Solicitud aceptada = solicitudRepository.save(solicitud);

        AcuerdoCompensacion acuerdo = AcuerdoCompensacion.builder()
                .solicitud(aceptada)
                .terminos(terminosAcuerdo(aceptada))
                .estado(EstadoAcuerdo.BORRADOR)
                .build();
        acuerdoCompensacionRepository.save(acuerdo);
        aceptada.setAcuerdo(acuerdo);

        notificacionService.notificar(solicitud.getPasajero(), TipoNotificacion.EXITO,
                "Solicitud aceptada",
                conductor.getNombre() + " aceptó tu solicitud para el viaje del "
                        + viaje.getDia() + " " + viaje.getFecha() + " a las " + viaje.getHora()
                        + ". Se generó un borrador de acuerdo de compensación ("
                        + solicitud.getPreferenciaCompensacion() + ").");

        return solicitudMapper.toSolicitudRespuesta(aceptada);
    }

    @Transactional
    public SolicitudRespuesta rechazar(Long id, RechazarRequest request) {
        Usuario conductor = usuarioService.usuarioActual();
        Solicitud solicitud = solicitudRepository.findByIdAndViajeRutaConductorId(id, conductor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Solicitud no encontrada"));
        validarSolicitudProcesable(solicitud);

        return rechazarInterna(solicitud, request == null ? null : request.motivo(), conductor);
    }

    @Transactional
    public List<SolicitudRespuesta> rechazarMultiple(RechazarMultipleRequest request) {
        Usuario conductor = usuarioService.usuarioActual();
        List<SolicitudRespuesta> resultados = new ArrayList<>();

        for (Long id : new LinkedHashSet<>(request.ids())) {
            Solicitud solicitud = solicitudRepository.findByIdAndViajeRutaConductorId(id, conductor.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Solicitud no encontrada: " + id));
            validarSolicitudProcesable(solicitud);
            resultados.add(rechazarInterna(solicitud, request.motivo(), conductor));
        }
        return resultados;
    }

    private SolicitudRespuesta rechazarInterna(Solicitud solicitud, String motivo, Usuario conductor) {
        Viaje viaje = solicitud.getViaje();
        solicitud.setEstado(EstadoSolicitud.RECHAZADA);
        solicitud.setMotivoRechazo(normalizarMotivo(motivo));
        Solicitud rechazada = solicitudRepository.save(solicitud);

        String mensaje = conductor.getNombre() + " revisó tu solicitud y la rechazó para el viaje del "
                + viaje.getDia() + " " + viaje.getFecha() + " a las " + viaje.getHora() + "."
                + (rechazada.getMotivoRechazo() != null
                        ? " Motivo: " + rechazada.getMotivoRechazo()
                        : "");
        notificacionService.notificar(solicitud.getPasajero(), TipoNotificacion.ADVERTENCIA,
                "Solicitud rechazada", mensaje);

        return solicitudMapper.toSolicitudRespuesta(rechazada);
    }

    private void validarSolicitudProcesable(Solicitud solicitud) {
        Viaje viaje = solicitud.getViaje();

        if (solicitud.getEstado() != EstadoSolicitud.PENDIENTE) {
            throw new BusinessException(
                    "La solicitud ya fue procesada; estado actual: " + solicitud.getEstado());
        }
        if (viaje.getEstado() != EstadoViaje.PROGRAMADO) {
            throw new BusinessException(
                    "El viaje ya no está activo; estado actual: " + viaje.getEstado());
        }
        if (viaje.getFecha().isBefore(LocalDate.now(ZoneId.systemDefault()))) {
            throw new BusinessException("El viaje ya ocurrió");
        }
    }

    private MetodoCompensacion validarMetodoCompensacion(SolicitudRequest request, Usuario pasajero) {
        if (request.metodoCompensacionId() == null) {
            return null;
        }
        MetodoCompensacion metodo = metodoCompensacionRepository
                .findByIdAndUsuarioId(request.metodoCompensacionId(), pasajero.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Método de compensación no encontrado"));
        if (!metodo.isActivo()) {
            throw new BusinessException("El método de compensación seleccionado está desactivado");
        }
        if (metodo.getTipo() != request.preferenciaCompensacion()) {
            throw new BusinessException("El método seleccionado (" + metodo.getTipo()
                    + ") no coincide con tu preferencia de compensación ("
                    + request.preferenciaCompensacion() + ")");
        }
        return metodo;
    }

    private String terminosAcuerdo(Solicitud solicitud) {
        Viaje viaje = solicitud.getViaje();
        return "Borrador de acuerdo de compensación\n"
                + "Viaje: " + viaje.getDia() + " " + viaje.getFecha() + " a las " + viaje.getHora() + "\n"
                + "Ruta: " + viaje.getRuta().getOrigen() + " → " + viaje.getRuta().getDestino() + "\n"
                + "Pasajero: " + solicitud.getPasajero().getNombre()
                + " (" + solicitud.getPasajero().getCorreoInstitucional() + ")\n"
                + "Conductor: " + viaje.getRuta().getConductor().getNombre() + "\n"
                + "Compensación propuesta: " + solicitud.getPreferenciaCompensacion() + "\n"
                + (solicitud.getMetodoCompensacion() != null
                        ? "Método de pago elegido: " + solicitud.getMetodoCompensacion().getTipo()
                                + " (" + solicitud.getMetodoCompensacion().getDescripcion() + ")\n"
                        : "")
                + "Pendiente de revisión y confirmación por ambas partes.";
    }

    private String normalizarMotivo(String motivo) {
        if (motivo == null || motivo.isBlank()) {
            return null;
        }
        return motivo.trim();
    }

    private String mensajeEstadoActual(Solicitud solicitud) {
        return switch (solicitud.getEstado()) {
            case CANCELADA -> "Tu solicitud ya fue cancelada";
            case ACEPTADA -> "Tu solicitud está ACEPTADA; solo las solicitudes pendientes pueden cancelarse";
            case RECHAZADA -> "Tu solicitud fue RECHAZADA; solo las solicitudes pendientes pueden cancelarse";
            case PENDIENTE -> "";
        };
    }

    private String advertenciaPreferencia(Usuario pasajero, Usuario conductor, SolicitudRequest request) {
        Perfil perfilConductor = perfilRepository.findByUsuarioId(conductor.getId()).orElse(null);
        if (perfilConductor == null || perfilConductor.getMetodoCompensacionFavorito() == null) {
            return null;
        }
        if (perfilConductor.getMetodoCompensacionFavorito() != request.preferenciaCompensacion()) {
            return "Tu preferencia de compensación (" + request.preferenciaCompensacion()
                    + ") difiere de la del conductor (" + perfilConductor.getMetodoCompensacionFavorito()
                    + "). Puedes enviarla igualmente.";
        }
        return null;
    }
}
