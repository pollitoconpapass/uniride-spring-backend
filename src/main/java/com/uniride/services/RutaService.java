package com.uniride.services;

import com.uniride.dto.requests.RutaRequest;
import com.uniride.dto.responses.RutaRespuesta;
import com.uniride.entities.Perfil;
import com.uniride.entities.Ruta;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoNotificacion;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.RutaMapper;
import com.uniride.repositories.PerfilRepository;
import com.uniride.repositories.RutaRepository;
import com.uniride.repositories.ViajeRepository;
import com.uniride.repositories.SolicitudRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RutaService {

    private final RutaRepository rutaRepository;
    private final RutaMapper rutaMapper;
    private final UsuarioService usuarioService;
    private final PerfilRepository perfilRepository;
    private final NotificacionService notificacionService;
    private final ViajeRepository viajeRepository;
    private final SolicitudRepository solicitudRepository;
    private final ViajeService viajeService;

    public RutaService(RutaRepository rutaRepository, RutaMapper rutaMapper,
            UsuarioService usuarioService, PerfilRepository perfilRepository,
            NotificacionService notificacionService, ViajeRepository viajeRepository,
            SolicitudRepository solicitudRepository, ViajeService viajeService) {
        this.rutaRepository = rutaRepository;
        this.rutaMapper = rutaMapper;
        this.usuarioService = usuarioService;
        this.perfilRepository = perfilRepository;
        this.notificacionService = notificacionService;
        this.viajeRepository = viajeRepository;
        this.solicitudRepository = solicitudRepository;
        this.viajeService = viajeService;
    }

    @Transactional
    public RutaRespuesta publicar(RutaRequest request) {
        Usuario conductor = usuarioService.usuarioActual();
        if (conductor.getRolPrincipal() != Rol.CONDUCTOR) {
            throw new NoPermitidoException("Debes tener el rol de conductor para publicar rutas");
        }

        Ruta ruta = rutaMapper.toRuta(request);
        ruta.setConductor(conductor);
        ruta.setDestino(universidadDelConductor(conductor));
        ruta.setEstado(EstadoRuta.ACTIVA);
        Ruta guardada = rutaRepository.save(ruta);

        notificacionService.notificar(conductor, TipoNotificacion.EXITO, "Ruta publicada",
                "Tu ruta " + ruta.getOrigen() + " → " + ruta.getDestino() + " fue publicada correctamente.");

        return rutaMapper.toRutaRespuesta(guardada);
    }

    @Transactional(readOnly = true)
    public Page<RutaRespuesta> listarMisRutas(EstadoRuta estado, int page, int size) {
        Usuario conductor = usuarioService.usuarioActual();
        PageRequest pageable = PageRequest.of(
                page, size, Sort.by("fechaCreacion").descending());

        Page<Ruta> rutas;

        if (estado == null) {
            // Por defecto, ocultar las rutas eliminadas.
            rutas = rutaRepository.findByConductorIdAndEstadoNot(
                    conductor.getId(), EstadoRuta.ELIMINADA, pageable);
        } else {
            // Permitir consultar cualquier estado, incluso ELIMINADA.
            rutas = rutaRepository.buscarPorConductorYEstado(
                    conductor.getId(), estado, pageable);
        }

        return rutas.map(rutaMapper::toRutaRespuesta);
    }

    @Transactional
    public RutaRespuesta actualizar(Long id, RutaRequest request, boolean confirmarImpacto) {
        Usuario conductor = usuarioService.usuarioActual();
        Ruta ruta = rutaRepository.findByIdAndConductorId(id, conductor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Ruta no encontrada"));

        if (ruta.getEstado() == EstadoRuta.ELIMINADA) {
            throw new BusinessException("No se puede editar una ruta eliminada");
        }
        if (request == null || request.origen() == null || request.origen().isBlank()
                || request.puntosReferencia() == null || request.puntosReferencia().isBlank()
                || request.capacidadMaxima() == null || request.capacidadMaxima() < 1) {
            throw new CamposInvalidosException("Completa origen, puntos de referencia y capacidad mayor a cero");
        }

        boolean cambioRelevante = ruta.getCapacidadMaxima() != request.capacidadMaxima()
                || !Objects.equals(ruta.getOrigen(), request.origen())
                || !Objects.equals(ruta.getPuntosReferencia(), request.puntosReferencia())
                || !Objects.equals(ruta.getZonasSinParada(), request.zonasSinParada());

        Map<Long, Usuario> pasajerosAfectados = new LinkedHashMap<>();
        if (cambioRelevante) {
            for (Viaje viaje : viajeRepository.findByRutaId(id)) {
                if (viaje.getEstado() == EstadoViaje.EN_PROGRESO) {
                    throw new BusinessException("No puedes cambiar detalles de una ruta con viajes en progreso");
                }
                if (viaje.getEstado() != EstadoViaje.PROGRAMADO) {
                    continue; // No modificar reglas sobre viajes históricos.
                }
                List<Solicitud> aceptadas = solicitudRepository.findByViajeIdAndEstadoIn(
                        viaje.getId(), List.of(EstadoSolicitud.ACEPTADA));
                if (aceptadas.size() > request.capacidadMaxima()) {
                    throw new BusinessException("La capacidad no puede ser inferior a los "
                            + aceptadas.size() + " pasajeros aceptados de un viaje");
                }
                for (Solicitud solicitud : aceptadas) {
                    pasajerosAfectados.put(solicitud.getPasajero().getId(), solicitud.getPasajero());
                }
            }
            if (!pasajerosAfectados.isEmpty() && !confirmarImpacto) {
                throw new BusinessException("ADVERTENCIA: la edición de la ruta afecta a "
                        + pasajerosAfectados.size() + " pasajero(s) aceptado(s). "
                        + "Confirma usando ?confirmarImpacto=true.");
            }
        }

        rutaMapper.actualizarRuta(request, ruta);
        ruta.setDestino(universidadDelConductor(conductor));
        Ruta guardada = rutaRepository.save(ruta);

        for (Usuario pasajero : pasajerosAfectados.values()) {
            notificacionService.notificar(pasajero, TipoNotificacion.ADVERTENCIA,
                    "Ruta modificada",
                    "El conductor actualizó detalles de tu ruta " + ruta.getOrigen()
                            + " → " + ruta.getDestino()
                            + ". Revisa el origen, los puntos de referencia y la capacidad.");
        }
        notificacionService.notificar(conductor, TipoNotificacion.EXITO, "Ruta actualizada",
                "Tu ruta " + ruta.getOrigen() + " → " + ruta.getDestino()
                        + " fue actualizada correctamente.");
        return rutaMapper.toRutaRespuesta(guardada);
    }

    @Transactional
    public void eliminar(Long id, boolean confirmar) {
        Usuario conductor = usuarioService.usuarioActual();
        Ruta ruta = rutaRepository.findByIdAndConductorId(id, conductor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Ruta no encontrada"));

        if (ruta.getEstado() == EstadoRuta.ELIMINADA) {
            notificacionService.notificar(conductor, TipoNotificacion.EXITO,
                    "Ruta ya eliminada", "La ruta ya había sido eliminada previamente.");
            return;
        }

        List<Viaje> viajes = viajeRepository.findByRutaId(id);
        LocalDateTime ahora = LocalDateTime.now(ZoneId.systemDefault());

        // Nunca interrumpir un viaje que ya está en ejecución.
        if (viajes.stream().anyMatch(v -> v.getEstado() == EstadoViaje.EN_PROGRESO)) {
            throw new BusinessException("No puedes eliminar una ruta con viajes en progreso");
        }

        boolean tienePasajerosAceptados = viajes.stream().anyMatch(v ->
                solicitudRepository.countByViajeIdAndEstado(v.getId(), EstadoSolicitud.ACEPTADA) > 0);

        boolean salidaInminente = viajes.stream()
                .filter(v -> v.getEstado() == EstadoViaje.PROGRAMADO)
                .anyMatch(v -> {
                    LocalDateTime salida = LocalDateTime.of(v.getFecha(), v.getHora());
                    Duration restante = Duration.between(ahora, salida);
                    return !restante.isNegative() && restante.compareTo(Duration.ofHours(4)) < 0;
                });

        if (!confirmar && (tienePasajerosAceptados || salidaInminente)) {
            String advertencia = salidaInminente
                    ? "ADVERTENCIA: existe un viaje a menos de 4 horas de la salida. "
                    : "";
            throw new BusinessException(advertencia
                    + "La eliminación afectará a pasajeros o viajes próximos. "
                    + "Confirma la operación usando ?confirmar=true.");
        }

        // Se cancela mediante la regla existente US18, conservando avisos y penalidades.
        // Los viajes ya finalizados/cancelados y las penalidades se conservan como historial.
        for (Viaje viaje : viajes) {
            if (viaje.getEstado() == EstadoViaje.PROGRAMADO) {
                LocalDateTime salida = LocalDateTime.of(viaje.getFecha(), viaje.getHora());
                if (!salida.isBefore(ahora)) {
                    viajeService.cancelar(viaje.getId());
                }
            }
        }

        // Eliminación lógica para respetar las claves foráneas de viajes, solicitudes e historial.
        ruta.setEstado(EstadoRuta.ELIMINADA);
        rutaRepository.save(ruta);
        notificacionService.notificar(conductor, TipoNotificacion.EXITO,
                "Ruta eliminada", "La ruta " + ruta.getOrigen() + " → "
                        + ruta.getDestino() + " dejó de estar disponible.");
    }

    private String universidadDelConductor(Usuario conductor) {
        Perfil perfil = perfilRepository.findByUsuarioId(conductor.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Primero completa tu perfil con tu universidad para publicar rutas"));
        if (perfil.getUniversidad() == null || perfil.getUniversidad().isBlank()) {
            throw new CamposInvalidosException("Debes indicar tu universidad en el perfil para publicar rutas");
        }
        return perfil.getUniversidad();
    }
}
