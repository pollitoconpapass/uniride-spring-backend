package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.uniride.dto.requests.LoginRequest;
import com.uniride.dto.requests.RegistroRequest;
import com.uniride.dto.requests.VerificarCodigoRequest;
import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.NoAutorizadoException;
import com.uniride.mappers.UsuarioMapper;
import com.uniride.repositories.UsuarioRepository;
import com.uniride.security.JwtService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class CierreTotal114AutenticacionTest {
    @Mock UsuarioRepository usuarios;
    @Mock PasswordEncoder encoder;
    @Mock JwtService jwt;
    @Mock NotificacionService notificaciones;
    @Mock UsuarioMapper mapper;
    @Mock MensajeService mensajes;
    @Mock PerfilRolService perfiles;
    private AuthService servicio;
    private Usuario usuario;

    @BeforeEach
    void preparar() {
        servicio = new AuthService(usuarios, encoder, jwt, notificaciones, mapper, mensajes, perfiles);
        usuario = Usuario.builder().id(10L).nombre("Lucía").correoInstitucional("lucia@upc.edu.pe")
                .contrasenaHash("hash").rolPrincipal(Rol.PASAJERO).cuentaVerificada(true).build();
    }

    @Test
    void loginFallidoRegistraErrorEnTransaccionIndependiente() {
        when(usuarios.findByCorreoInstitucional("lucia@upc.edu.pe")).thenReturn(Optional.of(usuario));
        when(encoder.matches("incorrecta", "hash")).thenReturn(false);
        assertThatThrownBy(() -> servicio.iniciarSesion(
                new LoginRequest("lucia@upc.edu.pe", "incorrecta")))
                .isInstanceOf(NoAutorizadoException.class);
        verify(notificaciones).notificarErrorAutenticacion(eq(usuario), any(), any());
        verify(notificaciones, never()).notificar(eq(usuario), any(), any(), any());
    }

    @Test
    void registroDuplicadoNoPierdeSuAvisoPorRollback() {
        when(usuarios.existsByCorreoInstitucional("lucia@upc.edu.pe")).thenReturn(true);
        when(usuarios.findByCorreoInstitucional("lucia@upc.edu.pe"))
                .thenReturn(Optional.of(usuario));
        RegistroRequest datos = new RegistroRequest("lucia@upc.edu.pe", "Aa123456!",
                "Lucía", "Pérez", "987654321", Rol.PASAJERO, true);
        assertThatThrownBy(() -> servicio.registrar(datos))
                .isInstanceOf(BusinessException.class);
        verify(notificaciones).notificarErrorAutenticacion(eq(usuario), any(), any());
        verify(usuarios, never()).save(any(Usuario.class));
    }

    @Test
    void cuentaYaVerificadaGuardaNotificacionAunqueRetorneConflicto() {
        when(usuarios.bloquearPorCorreo("lucia@upc.edu.pe")).thenReturn(Optional.of(usuario));
        assertThatThrownBy(() -> servicio.verificarCodigo(
                new VerificarCodigoRequest("lucia@upc.edu.pe", "123456")))
                .isInstanceOf(BusinessException.class);
        verify(notificaciones).notificarAccionYaRealizada(eq(usuario), any(), any());
    }
}
