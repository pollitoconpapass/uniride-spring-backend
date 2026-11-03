package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.uniride.entities.Ruta;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.EstadoViaje;
import com.uniride.exceptions.BusinessException;
import com.uniride.mappers.SolicitudMapper;
import com.uniride.repositories.AcuerdoCompensacionRepository;
import com.uniride.repositories.MetodoCompensacionRepository;
import com.uniride.repositories.PenalidadRepository;
import com.uniride.repositories.PerfilRepository;
import com.uniride.repositories.SolicitudRepository;
import com.uniride.repositories.ViajeRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SolicitudNotificacionUs25Test {
    @Mock SolicitudRepository solicitudes;
    @Mock ViajeRepository viajes;
    @Mock PerfilRepository perfiles;
    @Mock AcuerdoCompensacionRepository acuerdos;
    @Mock MetodoCompensacionRepository metodos;
    @Mock PenalidadRepository penalidades;
    @Mock SolicitudMapper mapper;
    @Mock UsuarioService usuarioService;
    @Mock NotificacionService notificaciones;
    private SolicitudService servicio;
    private Usuario conductor;
    private Usuario pasajero;
    private Viaje viaje;

    @BeforeEach
    void preparar() {
        servicio = new SolicitudService(solicitudes, viajes, perfiles, acuerdos, metodos,
                mapper, usuarioService, notificaciones, penalidades);
        conductor = Usuario.builder().id(1L).nombre("Conductor").build();
        pasajero = Usuario.builder().id(2L).nombre("Pasajero").build();
        Ruta ruta = Ruta.builder().id(10L).conductor(conductor).capacidadMaxima(3).build();
        viaje = Viaje.builder().id(20L).ruta(ruta).estado(EstadoViaje.PROGRAMADO)
                .fecha(LocalDate.now().plusDays(3)).hora(LocalTime.NOON).build();
    }

    @Test
    void solicitudDuplicadaNoCreaOtraYNotificaAlPasajero() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        when(viajes.findById(20L)).thenReturn(Optional.of(viaje));
        when(solicitudes.existsByViajeIdAndPasajeroIdAndEstadoIn(eq(20L), eq(2L), any()))
                .thenReturn(true);
        assertThatThrownBy(() -> servicio.crear(20L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Ya enviaste una solicitud");
        verify(notificaciones).notificarAccionYaRealizada(eq(pasajero),
                eq("Solicitud ya registrada"), anyString());
        verify(solicitudes, never()).save(any(Solicitud.class));
    }

    @Test
    void cancelacionRepetidaNotificaSinVolverAGuardar() {
        Solicitud solicitud = Solicitud.builder().id(55L).estado(EstadoSolicitud.CANCELADA)
                .pasajero(pasajero).viaje(viaje).build();
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        when(solicitudes.findByIdAndPasajeroId(55L, 2L)).thenReturn(Optional.of(solicitud));
        assertThatThrownBy(() -> servicio.cancelar(55L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ya fue cancelada");
        verify(notificaciones).notificarAccionYaRealizada(eq(pasajero),
                eq("Solicitud ya cancelada"), anyString());
        verify(solicitudes, never()).save(any(Solicitud.class));
    }

    @Test
    void aceptacionRepetidaNotificaSinDuplicarCupoNiAcuerdo() {
        Solicitud solicitud = Solicitud.builder().id(55L).estado(EstadoSolicitud.ACEPTADA)
                .pasajero(pasajero).viaje(viaje).build();
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(solicitudes.findByIdAndViajeRutaConductorId(55L, 1L))
                .thenReturn(Optional.of(solicitud));
        assertThatThrownBy(() -> servicio.aceptar(55L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ya fue procesada");
        verify(notificaciones).notificarAccionYaRealizada(eq(conductor),
                eq("Solicitud ya aceptada"), anyString());
        verify(viajes, never()).save(any(Viaje.class));
        verifyNoInteractions(acuerdos);
    }

    @Test
    void rechazoRepetidoNotificaSinDuplicarRechazo() {
        Solicitud solicitud = Solicitud.builder().id(55L).estado(EstadoSolicitud.RECHAZADA)
                .pasajero(pasajero).viaje(viaje).build();
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(solicitudes.findByIdAndViajeRutaConductorId(55L, 1L))
                .thenReturn(Optional.of(solicitud));
        assertThatThrownBy(() -> servicio.rechazar(55L, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ya fue procesada");
        verify(notificaciones).notificarAccionYaRealizada(eq(conductor),
                eq("Solicitud ya rechazada"), anyString());
        verify(solicitudes, never()).save(any(Solicitud.class));
    }
}
