package com.uniride.services;

import com.uniride.dto.responses.DetalleSolicitudRecibidaUs14Respuesta;
import com.uniride.dto.responses.PerfilPasajeroUs14Respuesta;
import com.uniride.dto.responses.RutaSolicitudesUs14Respuesta;
import com.uniride.dto.responses.SolicitudRecibidaUs14Respuesta;
import com.uniride.dto.responses.SolicitudesRecibidasUs14ResumenRespuesta;
import com.uniride.entities.Perfil;
import com.uniride.entities.Ruta;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.repositories.PerfilRepository;
import com.uniride.repositories.RutaRepository;
import com.uniride.repositories.SolicitudRepository;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SolicitudesRecibidasUs14Service {

    private final RutaRepository rutaRepository;
    private final SolicitudRepository solicitudRepository;
    private final PerfilRepository perfilRepository;
    private final UsuarioService usuarioService;

    public SolicitudesRecibidasUs14Service(RutaRepository rutaRepository,
            SolicitudRepository solicitudRepository, PerfilRepository perfilRepository,
            UsuarioService usuarioService) {
        this.rutaRepository = rutaRepository;
        this.solicitudRepository = solicitudRepository;
        this.perfilRepository = perfilRepository;
        this.usuarioService = usuarioService;
    }

    /**
     * US14: agrupar por rutas propias, incluso cuando una ruta no tiene solicitudes.
     * Se pagina por ruta ANTES de recuperar las solicitudes, para limitar el trabajo.
     */
    @Transactional(readOnly = true)
    public SolicitudesRecibidasUs14ResumenRespuesta porRuta(
            EstadoSolicitud estado, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new CamposInvalidosException("La pagina debe ser mayor o igual a 0 y size entre 1 y 100");
        }
        Usuario conductor = usuarioService.usuarioActual();
        Page<Ruta> rutas = rutaRepository.findByConductorId(conductor.getId(),
                PageRequest.of(page, size, Sort.by("fechaCreacion").descending()));
        Page<RutaSolicitudesUs14Respuesta> contenido = rutas.map(ruta -> {
            List<Solicitud> solicitudes = estado == null
                    ? solicitudRepository.findByViajeRutaIdOrderByFechaCreacionDesc(ruta.getId())
                    : solicitudRepository.findByViajeRutaIdAndEstadoOrderByFechaCreacionDesc(
                            ruta.getId(), estado);
            List<SolicitudRecibidaUs14Respuesta> elementos = solicitudes.stream()
                    .map(this::resumirSolicitud).toList();
            String mensaje = elementos.isEmpty()
                    ? "Aun no hay interesados en esta ruta. Revisa el origen, puntos de referencia "
                            + "y los horarios de tus viajes."
                    : null;
            return new RutaSolicitudesUs14Respuesta(ruta.getId(), ruta.getOrigen(),
                    ruta.getDestino(), ruta.getEstado(), elementos, mensaje);
        });
        String mensajeGeneral = null;
        if (rutas.getTotalElements() == 0) {
            mensajeGeneral = "Aun no tienes rutas publicadas. Publica una ruta para recibir solicitudes.";
        } else if (contenido.isEmpty()) {
            mensajeGeneral = "No hay rutas en esta pagina. Prueba una pagina anterior.";
        } else if (contenido.getContent().stream().allMatch(r -> r.solicitudes().isEmpty())) {
            mensajeGeneral = estado == null
                    ? "Aun no hay interesados en las rutas de esta pagina. Revisa sus detalles."
                    : "No hay solicitudes con estado " + estado + " en las rutas de esta pagina.";
        }
        return new SolicitudesRecibidasUs14ResumenRespuesta(contenido, mensajeGeneral);
    }

    /**
     * El filtro por conductor forma parte de la consulta: otro conductor no puede
     * consultar el perfil ni el mensaje de una solicitud ajena.
     */
    @Transactional(readOnly = true)
    public DetalleSolicitudRecibidaUs14Respuesta detalle(Long solicitudId) {
        Usuario conductor = usuarioService.usuarioActual();
        Solicitud solicitud = solicitudRepository
                .findByIdAndViajeRutaConductorId(solicitudId, conductor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Solicitud no encontrada"));
        Usuario pasajero = solicitud.getPasajero();
        Perfil perfil = perfilRepository.findByUsuarioId(pasajero.getId()).orElse(null);
        PerfilPasajeroUs14Respuesta resumen = new PerfilPasajeroUs14Respuesta(
                pasajero.getId(), pasajero.getNombre(), pasajero.getApellidos(),
                perfil == null ? null : perfil.getCarrera(),
                perfil == null ? null : perfil.getDistrito(),
                perfil == null ? null : perfil.getUniversidad(),
                perfil == null ? null : perfil.getGustos(),
                perfil == null ? null : perfil.getHobbies());
        return new DetalleSolicitudRecibidaUs14Respuesta(
                solicitud.getId(), solicitud.getViaje().getRuta().getId(),
                solicitud.getViaje().getId(), solicitud.getEstado(),
                solicitud.getMensaje(), resumen,
                perfil == null ? "El pasajero aun no ha completado su perfil." : null);
    }

    private SolicitudRecibidaUs14Respuesta resumirSolicitud(Solicitud solicitud) {
        Usuario pasajero = solicitud.getPasajero();
        Perfil perfil = perfilRepository.findByUsuarioId(pasajero.getId()).orElse(null);
        return new SolicitudRecibidaUs14Respuesta(
                solicitud.getId(), solicitud.getViaje().getId(),
                solicitud.getViaje().getFecha(), solicitud.getViaje().getHora(),
                solicitud.getEstado(), pasajero.getNombre(),
                perfil == null ? null : perfil.getCarrera(),
                perfil == null ? null : perfil.getDistrito(),
                solicitud.getMensaje(), solicitud.getFechaCreacion());
    }
}
