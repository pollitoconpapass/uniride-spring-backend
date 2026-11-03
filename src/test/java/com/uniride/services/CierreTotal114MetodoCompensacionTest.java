package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.uniride.entities.MetodoCompensacion;
import com.uniride.entities.Usuario;
import com.uniride.enums.TipoCompensacion;
import com.uniride.exceptions.BusinessException;
import com.uniride.mappers.MetodoCompensacionMapper;
import com.uniride.repositories.MetodoCompensacionRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CierreTotal114MetodoCompensacionTest {
    MetodoCompensacionRepository metodos = mock(MetodoCompensacionRepository.class);
    MetodoCompensacionMapper mapper = mock(MetodoCompensacionMapper.class);
    UsuarioService usuarios = mock(UsuarioService.class);
    NotificacionService notificaciones = mock(NotificacionService.class);
    MetodoCompensacionService servicio = new MetodoCompensacionService(
            metodos, mapper, usuarios, notificaciones);
    Usuario usuario = Usuario.builder().id(3L).nombre("A").build();
    MetodoCompensacion metodo = MetodoCompensacion.builder().id(9L).usuario(usuario)
            .tipo(TipoCompensacion.DINERO).descripcion("Yape").build();

    @Test
    void noEliminaMetodoConSolicitudesPendientesOHistoricas() {
        when(usuarios.usuarioActual()).thenReturn(usuario);
        when(metodos.findByIdAndUsuarioId(9L, 3L)).thenReturn(Optional.of(metodo));
        when(metodos.estaAsociadoACualquierSolicitud(9L)).thenReturn(true);
        assertThatThrownBy(() -> servicio.eliminar(9L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("solicitudes asociadas");
        verify(metodos, never()).delete(any(MetodoCompensacion.class));
    }

    @Test
    void eliminaMetodoQueNingunaSolicitudReferencia() {
        when(usuarios.usuarioActual()).thenReturn(usuario);
        when(metodos.findByIdAndUsuarioId(9L, 3L)).thenReturn(Optional.of(metodo));
        servicio.eliminar(9L);
        verify(metodos).delete(metodo);
        verify(notificaciones).notificar(eq(usuario), any(), anyString(), anyString());
    }
}
