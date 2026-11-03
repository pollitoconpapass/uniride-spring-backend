package com.uniride.services;

import com.uniride.dto.requests.InfoAdicionalRequest;
import com.uniride.dto.requests.PerfilRequest;
import com.uniride.dto.responses.PerfilRespuesta;
import com.uniride.entities.Perfil;
import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoNotificacion;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.PerfilMapper;
import com.uniride.repositories.PerfilRepository;
import com.uniride.repositories.SolicitudRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PerfilService {

    private final PerfilRepository perfilRepository;
    private final PerfilMapper perfilMapper;
    private final UsuarioService usuarioService;
    private final NotificacionService notificacionService;
    private final SolicitudRepository solicitudRepository;

    public PerfilService(PerfilRepository perfilRepository, PerfilMapper perfilMapper,
            UsuarioService usuarioService, NotificacionService notificacionService,
            SolicitudRepository solicitudRepository) {
        this.perfilRepository = perfilRepository;
        this.perfilMapper = perfilMapper;
        this.usuarioService = usuarioService;
        this.notificacionService = notificacionService;
        this.solicitudRepository = solicitudRepository;
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
        Usuario conductor = usuarioService.usuarioActual();
        if (!conductor.getId().equals(usuarioId)
                && !solicitudRepository.existeSolicitudPasajeroParaConductor(
                        usuarioId, conductor.getId())) {
            // No distinguir entre perfil inexistente y perfil ajeno.
            throw new ResourceNotFoundException("Perfil no encontrado");
        }
        Perfil perfil = perfilRepository.findByUsuarioId(usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Perfil no encontrado"));
        return perfilMapper.toPerfilRespuesta(perfil);
    }

    @Transactional
    public PerfilRespuesta guardarDatosPersonales(PerfilRequest request) {
        Usuario usuario = usuarioService.usuarioActual();

        if (request.metodoCompensacionFavorito() == null
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
        if (request == null) {
            throw new CamposInvalidosException("Debes ingresar información adicional");
        }

        Usuario usuario = usuarioService.usuarioActual();
        Perfil perfil = perfilRepository.findByUsuarioId(usuario.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Primero completa tu perfil personal"));

        // Al editar, un campo omitido (null) conserva su valor. Una cadena vacía
        // enviada explícitamente no elimina silenciosamente un dato obligatorio.
        String gustos = valorResultante(request.gustos(), perfil.getGustos());
        String hobbies = valorResultante(request.hobbies(), perfil.getHobbies());
        String datosCuriosos = valorResultante(request.datosCuriosos(), perfil.getDatosCuriosos());

        List<String> faltantes = new ArrayList<>();
        if (gustos == null || gustos.isBlank()) {
            faltantes.add("gustos");
        }
        if (hobbies == null || hobbies.isBlank()) {
            faltantes.add("hobbies");
        }
        if (datosCuriosos == null || datosCuriosos.isBlank()) {
            faltantes.add("datosCuriosos");
        }
        if (!faltantes.isEmpty()) {
            throw new CamposInvalidosException(
                    "Completa los campos obligatorios: " + String.join(", ", faltantes));
        }

        if (request.gustos() == null && request.hobbies() == null
                && request.datosCuriosos() == null) {
            throw new CamposInvalidosException("Debes indicar al menos un campo para actualizar");
        }

        perfil.setGustos(gustos);
        perfil.setHobbies(hobbies);
        perfil.setDatosCuriosos(datosCuriosos);
        Perfil guardado = perfilRepository.save(perfil);

        notificacionService.notificar(usuario, TipoNotificacion.EXITO, "Información adicional actualizada",
                "Tus gustos, hobbies y datos curiosos se guardaron correctamente.");

        return perfilMapper.toPerfilRespuesta(guardado);
    }

    private String valorResultante(String valorNuevo, String valorActual) {
        String valor = valorNuevo != null ? valorNuevo : valorActual;
        return valor == null ? null : valor.trim();
    }
}
