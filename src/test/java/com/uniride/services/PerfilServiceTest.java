package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.uniride.dto.requests.InfoAdicionalRequest;
import com.uniride.entities.Perfil;
import com.uniride.entities.Usuario;
import com.uniride.enums.TipoNotificacion;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.PerfilMapper;
import com.uniride.repositories.PerfilRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PerfilServiceTest {

    private PerfilRepository perfilRepository;
    private PerfilMapper perfilMapper;
    private UsuarioService usuarioService;
    private NotificacionService notificacionService;
    private PerfilService service;
    private Perfil perfil;

    @BeforeEach
    void preparar() {
        perfilRepository = mock(PerfilRepository.class);
        perfilMapper = mock(PerfilMapper.class);
        usuarioService = mock(UsuarioService.class);
        notificacionService = mock(NotificacionService.class);
        service = new PerfilService(perfilRepository, perfilMapper, usuarioService, notificacionService);
        Usuario usuario = Usuario.builder().id(7L).build();
        perfil = Perfil.builder().id(3L).usuario(usuario).build();
        when(usuarioService.usuarioActual()).thenReturn(usuario);
        when(perfilRepository.findByUsuarioId(7L)).thenReturn(Optional.of(perfil));
        when(perfilRepository.save(any(Perfil.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void us05ExitosoGuardaTresCamposYNotifica() {
        service.guardarInfoAdicional(new InfoAdicionalRequest(" Música ", " Natación ", " Soy zurdo "));

        assertThat(perfil.getGustos()).isEqualTo("Música");
        assertThat(perfil.getHobbies()).isEqualTo("Natación");
        assertThat(perfil.getDatosCuriosos()).isEqualTo("Soy zurdo");
        verify(perfilRepository).save(perfil);
        verify(notificacionService).notificar(eq(perfil.getUsuario()), eq(TipoNotificacion.EXITO),
                eq("Información adicional actualizada"), anyString());
    }

    @Test
    void us05ErrorIndicaCamposFaltantesEnPrimeraCarga() {
        assertThatThrownBy(() -> service.guardarInfoAdicional(new InfoAdicionalRequest(null, "", " ")))
                .isInstanceOf(CamposInvalidosException.class)
                .hasMessageContaining("gustos")
                .hasMessageContaining("hobbies")
                .hasMessageContaining("datosCuriosos");
        verify(perfilRepository, never()).save(any());
        verifyNoInteractions(notificacionService);
    }

    @Test
    void us05ErrorCampoEnBlancoNoSePersiste() {
        assertThatThrownBy(() -> service.guardarInfoAdicional(
                new InfoAdicionalRequest("Música", "  ", "Soy zurdo")))
                .isInstanceOf(CamposInvalidosException.class)
                .hasMessageContaining("hobbies");
        verify(perfilRepository, never()).save(any());
    }

    @Test
    void us05AlternativoPermiteEditarSoloUnCampoSinBorrarOtros() {
        perfil.setGustos("Música");
        perfil.setHobbies("Natación");
        perfil.setDatosCuriosos("Soy zurdo");

        service.guardarInfoAdicional(new InfoAdicionalRequest(null, " Ciclismo ", null));

        assertThat(perfil.getGustos()).isEqualTo("Música");
        assertThat(perfil.getHobbies()).isEqualTo("Ciclismo");
        assertThat(perfil.getDatosCuriosos()).isEqualTo("Soy zurdo");
        verify(perfilRepository).save(perfil);
    }

    @Test
    void us05EdicionNoPermiteVaciarUnDatoObligatorio() {
        perfil.setGustos("Música");
        perfil.setHobbies("Natación");
        perfil.setDatosCuriosos("Soy zurdo");

        assertThatThrownBy(() -> service.guardarInfoAdicional(new InfoAdicionalRequest(" ", null, null)))
                .isInstanceOf(CamposInvalidosException.class)
                .hasMessageContaining("gustos");
        assertThat(perfil.getGustos()).isEqualTo("Música");
        verify(perfilRepository, never()).save(any());
    }

    @Test
    void us05EdicionSinCambiosDevuelve400() {
        perfil.setGustos("Música");
        perfil.setHobbies("Natación");
        perfil.setDatosCuriosos("Soy zurdo");

        assertThatThrownBy(() -> service.guardarInfoAdicional(new InfoAdicionalRequest(null, null, null)))
                .isInstanceOf(CamposInvalidosException.class)
                .hasMessage("Debes indicar al menos un campo para actualizar");
        verify(perfilRepository, never()).save(any());
    }

    @Test
    void us05SinPerfilPersonalDevuelve404() {
        when(perfilRepository.findByUsuarioId(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.guardarInfoAdicional(new InfoAdicionalRequest("A", "B", "C")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Primero completa tu perfil personal");
        verify(perfilRepository, never()).save(any());
    }

    @Test
    void us05RequestNuloDevuelve400() {
        assertThatThrownBy(() -> service.guardarInfoAdicional(null))
                .isInstanceOf(CamposInvalidosException.class)
                .hasMessageContaining("información adicional");
        verify(perfilRepository, never()).save(any());
    }
}
