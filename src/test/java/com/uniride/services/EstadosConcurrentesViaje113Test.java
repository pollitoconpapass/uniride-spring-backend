package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.uniride.entities.Ruta;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.EstadoViaje;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.mappers.ViajeMapper;
import com.uniride.repositories.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EstadosConcurrentesViaje113Test {
    @Mock ViajeRepository viajes;
    @Mock RutaRepository rutas;
    @Mock SolicitudRepository solicitudes;
    @Mock PenalidadRepository penalidades;
    @Mock ViajeMapper mapper;
    @Mock UsuarioService usuarios;
    @Mock NotificacionService notificaciones;
    ViajeService servicio;
    Usuario conductor;
    Usuario pasajero;
    Viaje viaje;

    @BeforeEach void preparar() {
        servicio = new ViajeService(viajes, rutas, solicitudes, penalidades, mapper, usuarios, notificaciones);
        conductor = Usuario.builder().id(1L).nombre("Conductor").build();
        pasajero = Usuario.builder().id(2L).nombre("Pasajero").build();
        Ruta ruta = Ruta.builder().id(3L).conductor(conductor).origen("Surco")
                .destino("UPC").capacidadMaxima(1).build();
        LocalDateTime salida = LocalDateTime.now().plusDays(2);
        viaje = Viaje.builder().id(4L).ruta(ruta).estado(EstadoViaje.PROGRAMADO)
                .fecha(salida.toLocalDate()).hora(salida.toLocalTime()).dia("Miercoles").build();
    }

    @Test void cancelarUsaBloqueoYNoCambiaViajeAjeno() {
        when(usuarios.usuarioActual()).thenReturn(pasajero);
        when(viajes.bloquearParaAceptar(4L)).thenReturn(Optional.of(viaje));
        assertThatThrownBy(() -> servicio.cancelar(4L)).isInstanceOf(NoPermitidoException.class);
        verify(viajes, never()).save(any(Viaje.class));
        verifyNoInteractions(penalidades, notificaciones);
    }

    @Test void cancelarConductorBloqueaAntesDeMutarYConservaHistorial() {
        when(usuarios.usuarioActual()).thenReturn(conductor);
        when(viajes.bloquearParaAceptar(4L)).thenReturn(Optional.of(viaje));
        when(solicitudes.findByViajeIdAndEstadoIn(eq(4L), any())).thenReturn(List.of());
        when(viajes.save(any(Viaje.class))).thenAnswer(inv -> inv.getArgument(0));
        servicio.cancelar(4L);
        assertThat(viaje.getEstado()).isEqualTo(EstadoViaje.CANCELADO);
        assertThat(viaje.getPasajeros()).isZero();
        var order = inOrder(viajes);
        order.verify(viajes).bloquearParaAceptar(4L);
        order.verify(viajes).save(viaje);
    }

    @Test void cancelarViajeYaCanceladoNoGrabaNiPenaliza() {
        viaje.setEstado(EstadoViaje.CANCELADO);
        when(usuarios.usuarioActual()).thenReturn(conductor);
        when(viajes.bloquearParaAceptar(4L)).thenReturn(Optional.of(viaje));
        servicio.cancelar(4L);
        verify(viajes, never()).save(any(Viaje.class));
        verifyNoInteractions(penalidades);
    }

    @Test void confirmarUsaBloqueoConPropiedadDelConductor() {
        viaje.setConfirmado(true);
        when(usuarios.usuarioActual()).thenReturn(conductor);
        when(viajes.bloquearParaConductor(4L, 1L)).thenReturn(Optional.of(viaje));
        servicio.confirmar(4L);
        verify(viajes).bloquearParaConductor(4L, 1L);
        verify(viajes, never()).save(any(Viaje.class));
    }

    @Test void iniciarNoPermiteViajeCanceladoAunqueEsteConfirmado() {
        viaje.setConfirmado(true);
        viaje.setEstado(EstadoViaje.CANCELADO);
        when(usuarios.usuarioActual()).thenReturn(conductor);
        when(viajes.bloquearParaConductor(4L, 1L)).thenReturn(Optional.of(viaje));
        assertThatThrownBy(() -> servicio.iniciar(4L)).isInstanceOf(BusinessException.class);
        verify(viajes, never()).save(any(Viaje.class));
    }

    @Test void completarNoPermiteViajeQueSigueProgramado() {
        when(usuarios.usuarioActual()).thenReturn(conductor);
        when(viajes.bloquearParaConductor(4L, 1L)).thenReturn(Optional.of(viaje));
        assertThatThrownBy(() -> servicio.completar(4L)).isInstanceOf(BusinessException.class);
        verify(viajes, never()).save(any(Viaje.class));
    }
}
