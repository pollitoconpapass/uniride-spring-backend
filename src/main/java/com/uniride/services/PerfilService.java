package com.uniride.services;

import com.uniride.dto.InfoAdicionalRequest;
import com.uniride.dto.PerfilRequest;
import com.uniride.dto.PerfilRespuesta;
import com.uniride.entities.Perfil;
import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoNotificacion;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.PerfilMapper;
import com.uniride.repositories.PerfilRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PerfilService {

    private final PerfilRepository perfilRepository;
    private final PerfilMapper perfilMapper;
    private final UsuarioService usuarioService;
    private final NotificacionService notificacionService;

    public PerfilService(PerfilRepository perfilRepository, PerfilMapper perfilMapper,
            UsuarioService usuarioService, NotificacionService notificacionService) {
        this.perfilRepository = perfilRepository;
        this.perfilMapper = perfilMapper;
        this.usuarioService = usuarioService;
        this.notificacionService = notificacionService;
    }

    @Transactional(readOnly = true)
    public PerfilRespuesta obtenerPerfil() {
        Usuario usuario = usuarioService.usuarioActual();
        Perfil perfil = perfilRepository.findByUsuarioId(usuario.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Aún no has completado tu perfil"));
        return perfilMapper.toPerfilRespuesta(perfil);
    }

    @Transactional(readOnly = true)
    public PerfilRespuesta perfilDeUsuario(Long usuarioId) {
        Perfil perfil = perfilRepository.findByUsuarioId(usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Perfil no encontrado"));
        return perfilMapper.toPerfilRespuesta(perfil);
    }

    @Transactional
    public PerfilRespuesta guardarDatosPersonales(PerfilRequest request) {
        Usuario usuario = usuarioService.usuarioActual();

        if (request.getMetodoCompensacionFavorito() == null
                && usuario.getRolPrincipal() == Rol.CONDUCTOR) { // -> solo los conductores tienen metodo de compensacion favorito
            throw new CamposInvalidosException(
                    "El método de compensación favorito es obligatorio para conductores");
        }

        Perfil perfil = perfilRepository.findByUsuarioId(usuario.getId()).orElse(null);

        boolean esNuevo = perfil == null;
        if (esNuevo) {
            perfil = perfilMapper.toPerfil(request);
            perfil.setUsuario(usuario);
        } else {
            perfilMapper.actualizarPerfil(request, perfil);
        }

        Perfil guardado = perfilRepository.save(perfil);

        notificacionService.notificar(usuario, TipoNotificacion.EXITO,
                esNuevo ? "Perfil creado" : "Perfil actualizado",
                "Tu información personal se guardó correctamente.");

        return perfilMapper.toPerfilRespuesta(guardado);
    }

    @Transactional
    public PerfilRespuesta guardarInfoAdicional(InfoAdicionalRequest request) {
        Usuario usuario = usuarioService.usuarioActual();
        Perfil perfil = perfilRepository.findByUsuarioId(usuario.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Primero completa tu perfil personal"));

        perfilMapper.actualizarInfoAdicional(request, perfil);
        Perfil guardado = perfilRepository.save(perfil);

        notificacionService.notificar(usuario, TipoNotificacion.EXITO, "Información adicional actualizada",
                "Tus gustos, hobbies y datos curiosos se guardaron correctamente.");

        return perfilMapper.toPerfilRespuesta(guardado);
    }
}
