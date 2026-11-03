package com.uniride.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import com.uniride.repositories.UsuarioRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

class CierreTotal114SeguridadTest {
    @AfterEach void limpiar() { SecurityContextHolder.clearContext(); }

    @Test
    void usuarioNoVerificadoNoEstaHabilitadoParaJWT() {
        UsuarioRepository repo = mock(UsuarioRepository.class);
        Usuario noVerificado = Usuario.builder().correoInstitucional("x@upc.edu.pe")
                .contrasenaHash("hash").rolPrincipal(Rol.PASAJERO).cuentaVerificada(false).build();
        when(repo.findByCorreoInstitucional("x@upc.edu.pe"))
                .thenReturn(Optional.of(noVerificado));
        assertThat(new UsuarioDetailsService(repo).loadUserByUsername("x@upc.edu.pe")
                .isEnabled()).isFalse();
    }

    @Test
    void usuarioVerificadoSiEstaHabilitadoParaJWT() {
        UsuarioRepository repo = mock(UsuarioRepository.class);
        Usuario verificado = Usuario.builder().correoInstitucional("x@upc.edu.pe")
                .contrasenaHash("hash").rolPrincipal(Rol.PASAJERO).cuentaVerificada(true).build();
        when(repo.findByCorreoInstitucional("x@upc.edu.pe"))
                .thenReturn(Optional.of(verificado));
        assertThat(new UsuarioDetailsService(repo).loadUserByUsername("x@upc.edu.pe")
                .isEnabled()).isTrue();
    }

    @Test
    void filtroIgnoraJWTDeCuentaNoVerificada() throws Exception {
        JwtService jwt = mock(JwtService.class);
        UsuarioDetailsService usuarios = mock(UsuarioDetailsService.class);
        UserDetails detalles = User.withUsername("x@upc.edu.pe").password("hash")
                .roles("PASAJERO").disabled(true).build();
        when(jwt.extraerCorreo("jwt-valido")).thenReturn("x@upc.edu.pe");
        when(usuarios.loadUserByUsername("x@upc.edu.pe")).thenReturn(detalles);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/usuarios/me");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer jwt-valido");
        new JwtAuthFilter(jwt, usuarios).doFilter(request,
                new MockHttpServletResponse(), new MockFilterChain());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void corsPermiteAngularYBloqueaOrigenDesconocido() {
        CorsConfigurationSource fuente = new SecurityConfig(null)
                .corsConfigurationSource("http://localhost:4200,http://127.0.0.1:4200");
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/auth/login");
        request.setServletPath("/auth/login");
        CorsConfiguration config = fuente.getCorsConfiguration(request);
        assertThat(config).isNotNull();
        assertThat(config.checkOrigin("http://localhost:4200")).isEqualTo("http://localhost:4200");
        assertThat(config.checkOrigin("https://sitio-desconocido.com")).isNull();
        assertThat(config.getExposedHeaders()).contains("X-UniRide-Mensaje");
        assertThat(config.getAllowedMethods()).contains("OPTIONS", "PUT", "DELETE");
    }
}
