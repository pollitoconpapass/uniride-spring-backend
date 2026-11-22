package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.uniride.dto.requests.MetodoCompensacionRequest;
import com.uniride.entities.MetodoCompensacion;
import com.uniride.entities.Usuario;
import com.uniride.enums.TipoCompensacion;
import com.uniride.exceptions.BusinessException;
import com.uniride.mappers.MetodoCompensacionMapper;
import com.uniride.repositories.MetodoCompensacionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CierreTotal114MetodoCompensacionTest {
    MetodoCompensacionRepository metodos = mock(MetodoCompensacionRepository.class);
    MetodoCompensacionMapper mapper = mock(MetodoCompensacionMapper.class);
    UsuarioService usuarios = mock(UsuarioService.class);
    NotificacionService notificaciones = mock(NotificacionService.class);
    EntityManager entityManager = mock(EntityManager.class);
    MetodoCompensacionService servicio = new MetodoCompensacionService(
            metodos, mapper, usuarios, notificaciones, entityManager);
    Usuario usuario = Usuario.builder().id(3L).nombre("A").build();
    MetodoCompensacion metodo = MetodoCompensacion.builder().id(9L).usuario(usuario)
            .tipo(TipoCompensacion.DINERO).descripcion("Yape").build();

    @Test
    void noEliminaMetodoConSolicitudesPendientesOHistoricas() {
        when(usuarios.usuarioActual()).thenReturn(usuario);
        when(metodos.bloquearPropio(9L, 3L)).thenReturn(Optional.of(metodo));
        when(metodos.estaAsociadoACualquierSolicitud(9L)).thenReturn(true);
        assertThatThrownBy(() -> servicio.eliminar(9L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("solicitudes asociadas");
        verify(metodos, never()).delete(any(MetodoCompensacion.class));
    }

    @Test
    void eliminaMetodoQueNingunaSolicitudReferencia() {
        when(usuarios.usuarioActual()).thenReturn(usuario);
        when(metodos.bloquearPropio(9L, 3L)).thenReturn(Optional.of(metodo));
        servicio.eliminar(9L);
        var orden = inOrder(entityManager, metodos, notificaciones);
        orden.verify(entityManager).refresh(usuario, LockModeType.PESSIMISTIC_WRITE);
        orden.verify(metodos).bloquearPropio(9L, 3L);
        orden.verify(entityManager).refresh(metodo);
        orden.verify(metodos).estaAsociadoACualquierSolicitud(9L);
        orden.verify(metodos).delete(metodo);
        orden.verify(notificaciones).notificar(eq(usuario), any(), anyString(), anyString());
    }

    @Test
    void validacionesReutilizanReglasSinBloquearEscribirNiNotificar() {
        when(usuarios.usuarioActual()).thenReturn(usuario);
        when(metodos.findByIdAndUsuarioId(9L, 3L)).thenReturn(Optional.of(metodo));
        when(metodos.existsByUsuarioIdAndTipoAndDescripcionIgnoreCaseAndIdNot(
                3L, TipoCompensacion.DINERO, "Otro método", 9L)).thenReturn(true);
        when(metodos.estaAsociadoACualquierSolicitud(9L)).thenReturn(true);

        assertThatThrownBy(() -> servicio.validarActualizacion(9L,
                new MetodoCompensacionRequest(TipoCompensacion.DINERO, " Otro método ", true)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("mismo tipo y descripción");
        assertThatThrownBy(() -> servicio.validarEliminacion(9L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("solicitudes asociadas");

        verifyNoInteractions(entityManager, mapper, notificaciones);
        verify(metodos, never()).save(any(MetodoCompensacion.class));
        verify(metodos, never()).delete(any(MetodoCompensacion.class));
    }
}
