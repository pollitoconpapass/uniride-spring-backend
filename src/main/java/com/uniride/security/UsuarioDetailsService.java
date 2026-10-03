package com.uniride.security;

import com.uniride.entities.Usuario;
import com.uniride.repositories.UsuarioRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UsuarioDetailsService implements UserDetailsService {

    private final UsuarioRepository usuarioRepository;

    public UsuarioDetailsService(UsuarioRepository usuarioRepository) {
        this.usuarioRepository = usuarioRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String correoInstitucional) {
        Usuario usuario = usuarioRepository.findByCorreoInstitucional(correoInstitucional)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Usuario no encontrado: " + correoInstitucional));

        String rol = usuario.getRolPrincipal() == null ? "USUARIO" : usuario.getRolPrincipal().name();

        return User.withUsername(usuario.getCorreoInstitucional())
                .password(usuario.getContrasenaHash())
                .roles(rol)
                .build();
    }
}
