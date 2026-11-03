package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.uniride.entities.Perfil;
import com.uniride.entities.Usuario;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.PerfilMapper;
import com.uniride.repositories.PerfilRepository;
import com.uniride.repositories.SolicitudRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CierreTotal114PrivacidadPerfilTest {
    PerfilRepository perfiles = mock(PerfilRepository.class);
    SolicitudRepository solicitudes = mock(SolicitudRepository.class);
    PerfilMapper mapper = mock(PerfilMapper.class);
    UsuarioService usuarios = mock(UsuarioService.class);
    NotificacionService notificaciones = mock(NotificacionService.class);
    PerfilService servicio = new PerfilService(perfiles, mapper, usuarios, notificaciones, solicitudes);

    @BeforeEach
    void preparar() {
        when(usuarios.usuarioActual()).thenReturn(Usuario.builder().id(7L).build());
    }

    @Test
    void unConductorNoPuedeConsultarPerfilAjenoSinSolicitudRelacionada() {
        assertThatThrownBy(() -> servicio.perfilDeUsuario(99L))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Perfil no encontrado");
        verify(solicitudes).existeSolicitudPasajeroParaConductor(99L, 7L);
        verify(perfiles, never()).findByUsuarioId(99L);
    }

    @Test
    void conductorPuedeVerPerfilDePasajeroQueLeSolicitoViaje() {
        when(solicitudes.existeSolicitudPasajeroParaConductor(99L, 7L)).thenReturn(true);
        when(perfiles.findByUsuarioId(99L)).thenReturn(Optional.of(Perfil.builder().id(5L).build()));
        servicio.perfilDeUsuario(99L);
        verify(mapper).toPerfilRespuesta(any(Perfil.class));
    }

    @Test
    void conductorPuedeConsultarSuPropioPerfil() {
        when(perfiles.findByUsuarioId(7L)).thenReturn(Optional.of(Perfil.builder().id(5L).build()));
        servicio.perfilDeUsuario(7L);
        verifyNoInteractions(solicitudes);
        verify(mapper).toPerfilRespuesta(any(Perfil.class));
    }
}
