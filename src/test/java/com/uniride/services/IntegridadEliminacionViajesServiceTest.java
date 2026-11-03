package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.uniride.entities.Ruta;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.TipoNotificacion;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.ViajeMapper;
import com.uniride.repositories.PenalidadRepository;
import com.uniride.repositories.RutaRepository;
import com.uniride.repositories.SolicitudRepository;
import com.uniride.repositories.ViajeRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IntegridadEliminacionViajesServiceTest {
    @Mock ViajeRepository viajes;
    @Mock RutaRepository rutas;
    @Mock SolicitudRepository solicitudes;
    @Mock PenalidadRepository penalidades;
    @Mock ViajeMapper mapper;
    @Mock UsuarioService usuarios;
    @Mock NotificacionService notificaciones;
    private ViajeService servicio;
    private Usuario conductor;
    private Viaje viaje;

    @BeforeEach
    void preparar() {
        servicio = new ViajeService(viajes, rutas, solicitudes, penalidades,
                mapper, usuarios, notificaciones);
        conductor = Usuario.builder().id(20L).nombre("Conductor").build();
        Ruta ruta = Ruta.builder().id(30L).conductor(conductor)
                .origen("Surco").destino("UPC").build();
        viaje = Viaje.builder().id(40L).ruta(ruta).dia("Lunes")
                .fecha(LocalDate.now().plusDays(2)).hora(LocalTime.of(7, 30))
                .estado(EstadoViaje.PROGRAMADO).build();
    }

    private void prepararBusqueda() {
        when(usuarios.usuarioActual()).thenReturn(conductor);
        when(viajes.findByIdAndRutaConductorId(40L, 20L))
                .thenReturn(Optional.of(viaje));
    }

    private void comprobarRechazo() {
        assertThatThrownBy(() -> servicio.eliminar(40L))
                .isInstanceOf(BusinessException.class);
        verify(viajes, never()).delete(any(Viaje.class));
        verify(viajes, never()).flush();
        verifyNoInteractions(notificaciones);
    }

    @Test
    void eliminaSoloViajeProgramadoSinSolicitudesNiPenalidades() {
        prepararBusqueda();
        servicio.eliminar(40L);
        verify(solicitudes).existsByViajeId(40L);
        verify(penalidades).existsByViajeId(40L);
        verify(viajes).delete(viaje);
        verify(viajes).flush();
        verify(notificaciones).notificar(eq(conductor), eq(TipoNotificacion.EXITO),
                eq("Viaje eliminado"), anyString());
    }

    @Test
    void rechazaViajeConSolicitudPendiente() {
        prepararBusqueda();
        when(solicitudes.existsByViajeId(40L)).thenReturn(true);
        comprobarRechazo();
        verify(penalidades, never()).existsByViajeId(anyLong());
    }

    @Test
    void rechazaViajeConSolicitudHistoricaInclusoCancelada() {
        prepararBusqueda();
        when(solicitudes.existsByViajeId(40L)).thenReturn(true);
        comprobarRechazo();
    }

    @Test
    void rechazaViajeConPenalidad() {
        prepararBusqueda();
        when(penalidades.existsByViajeId(40L)).thenReturn(true);
        comprobarRechazo();
    }

    @Test
    void rechazaViajeConfirmadoAunqueNoTengaSolicitudes() {
        viaje.setConfirmado(true);
        prepararBusqueda();
        comprobarRechazo();
        verifyNoInteractions(solicitudes, penalidades);
    }

    @Test
    void rechazaViajeEnProgreso() {
        viaje.setEstado(EstadoViaje.EN_PROGRESO);
        prepararBusqueda();
        comprobarRechazo();
    }

    @Test
    void rechazaViajeCompletado() {
        viaje.setEstado(EstadoViaje.COMPLETADO);
        prepararBusqueda();
        comprobarRechazo();
    }

    @Test
    void rechazaViajeCancelado() {
        viaje.setEstado(EstadoViaje.CANCELADO);
        prepararBusqueda();
        comprobarRechazo();
    }

    @Test
    void rechazaViajeVencido() {
        viaje.setEstado(EstadoViaje.VENCIDO);
        prepararBusqueda();
        comprobarRechazo();
    }

    @Test
    void noPermiteEliminarViajeDeOtroConductor() {
        when(usuarios.usuarioActual()).thenReturn(conductor);
        when(viajes.findByIdAndRutaConductorId(40L, 20L))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> servicio.eliminar(40L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(viajes, never()).delete(any(Viaje.class));
        verifyNoInteractions(notificaciones);
    }
}
