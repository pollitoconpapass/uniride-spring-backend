package com.uniride.services;

import com.uniride.dto.RutaRequest;
import com.uniride.dto.RutaRespuesta;
import com.uniride.entities.Perfil;
import com.uniride.entities.Ruta;
import com.uniride.entities.Usuario;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoNotificacion;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.RutaMapper;
import com.uniride.repositories.PerfilRepository;
import com.uniride.repositories.RutaRepository;
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

    public RutaService(RutaRepository rutaRepository, RutaMapper rutaMapper,
            UsuarioService usuarioService, PerfilRepository perfilRepository,
            NotificacionService notificacionService) {
        this.rutaRepository = rutaRepository;
        this.rutaMapper = rutaMapper;
        this.usuarioService = usuarioService;
        this.perfilRepository = perfilRepository;
        this.notificacionService = notificacionService;
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
        PageRequest pageable = PageRequest.of(page, size, Sort.by("fechaCreacion").descending());

        Page<Ruta> rutas = estado == null
                ? rutaRepository.findByConductorId(conductor.getId(), pageable)
                : rutaRepository.buscarPorConductorYEstado(conductor.getId(), estado, pageable);

        return rutas.map(rutaMapper::toRutaRespuesta);
    }

    @Transactional
    public RutaRespuesta actualizar(Long id, RutaRequest request) {
        Usuario conductor = usuarioService.usuarioActual();
        Ruta ruta = rutaRepository.findByIdAndConductorId(id, conductor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Ruta no encontrada"));

        rutaMapper.actualizarRuta(request, ruta);
        ruta.setDestino(universidadDelConductor(conductor));
        Ruta guardada = rutaRepository.save(ruta);

        notificacionService.notificar(conductor, TipoNotificacion.EXITO, "Ruta actualizada",
                "Tu ruta " + ruta.getOrigen() + " → " + ruta.getDestino() + " fue actualizada correctamente.");

        return rutaMapper.toRutaRespuesta(guardada);
    }

    @Transactional
    public void eliminar(Long id) {
        Usuario conductor = usuarioService.usuarioActual();
        Ruta ruta = rutaRepository.findByIdAndConductorId(id, conductor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Ruta no encontrada"));

        rutaRepository.delete(ruta);

        notificacionService.notificar(conductor, TipoNotificacion.EXITO, "Ruta eliminada",
                "Tu ruta " + ruta.getOrigen() + " → " + ruta.getDestino() + " fue eliminada.");
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
