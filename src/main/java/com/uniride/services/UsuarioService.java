package com.uniride.services;

import com.uniride.dto.requests.CambiarRolRequest;
import com.uniride.dto.responses.UsuarioRespuesta;
import com.uniride.entities.Usuario;
import com.uniride.exceptions.NoAutorizadoException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.UsuarioMapper;
import com.uniride.repositories.UsuarioRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UsuarioService {

    private final UsuarioRepository usuarioRepository;
    private final UsuarioMapper usuarioMapper;

    public UsuarioService(UsuarioRepository usuarioRepository, UsuarioMapper usuarioMapper) {
        this.usuarioRepository = usuarioRepository;
        this.usuarioMapper = usuarioMapper;
    }

    public Usuario usuarioActual() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion == null || autenticacion.getName() == null) {
            throw new NoAutorizadoException("Debes iniciar sesión para realizar esta acción");
        }
        return usuarioRepository.findByCorreoInstitucional(autenticacion.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
    }

    @Transactional(readOnly = true)
    public UsuarioRespuesta obtenerUsuarioActual() {
        return usuarioMapper.toUsuarioRespuesta(usuarioActual());
    }

    @Transactional
    public UsuarioRespuesta cambiarRol(CambiarRolRequest request) {
        Usuario usuario = usuarioActual();
        usuario.setRolPrincipal(request.rol());
        usuarioRepository.save(usuario);
        return usuarioMapper.toUsuarioRespuesta(usuario);
    }
}
