package com.uniride.services;

import com.uniride.dto.SolicitudRequest;
import com.uniride.dto.SolicitudRespuesta;
import com.uniride.entities.Perfil;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.TipoNotificacion;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.SolicitudMapper;
import com.uniride.repositories.PerfilRepository;
import com.uniride.repositories.SolicitudRepository;
import com.uniride.repositories.ViajeRepository;
import java.time.LocalDate;
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
    private final SolicitudMapper solicitudMapper;
    private final UsuarioService usuarioService;
    private final NotificacionService notificacionService;

    public SolicitudService(SolicitudRepository solicitudRepository, ViajeRepository viajeRepository,
            PerfilRepository perfilRepository, SolicitudMapper solicitudMapper,
            UsuarioService usuarioService, NotificacionService notificacionService) {
        this.solicitudRepository = solicitudRepository;
        this.viajeRepository = viajeRepository;
        this.perfilRepository = perfilRepository;
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
        if (viaje.getFecha().isBefore(LocalDate.now())) {
            throw new BusinessException("El viaje ya ocurrió");
        }
        if (solicitudRepository.existsByViajeIdAndPasajeroIdAndEstadoIn(
                viajeId, pasajero.getId(), ESTADOS_BLOQUEANTES)) {
            throw new BusinessException("Ya enviaste una solicitud para este viaje");
        }

        long aceptadas = solicitudRepository.countByViajeIdAndEstado(viajeId, EstadoSolicitud.ACEPTADA);
        if (aceptadas >= viaje.getRuta().getCapacidadMaxima()) {
            throw new BusinessException("La ruta ya no tiene cupos disponibles");
        }

        Solicitud solicitud = solicitudMapper.toSolicitud(request);
        solicitud.setViaje(viaje);
        solicitud.setPasajero(pasajero);
        solicitud.setEstado(EstadoSolicitud.PENDIENTE);
        Solicitud guardada = solicitudRepository.save(solicitud);

        notificacionService.notificar(conductor, TipoNotificacion.EXITO, "Nueva solicitud de viaje",
                pasajero.getNombre() + " solicitó unirse a tu viaje del "
                        + viaje.getDia() + " " + viaje.getFecha() + " a las " + viaje.getHora() + ".");

        SolicitudRespuesta respuesta = solicitudMapper.toSolicitudRespuesta(guardada);
        respuesta.setAdvertencia(advertenciaPreferencia(pasajero, conductor, request));
        return respuesta;
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
        if (solicitud.getViaje().getFecha().isBefore(LocalDate.now())) {
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
        if (perfilConductor.getMetodoCompensacionFavorito() != request.getPreferenciaCompensacion()) {
            return "Tu preferencia de compensación (" + request.getPreferenciaCompensacion()
                    + ") difiere de la del conductor (" + perfilConductor.getMetodoCompensacionFavorito()
                    + "). Puedes enviarla igualmente.";
        }
        return null;
    }
}
