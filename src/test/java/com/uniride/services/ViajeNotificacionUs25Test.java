package com.uniride.services;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.uniride.dto.requests.ViajeRequest;
import com.uniride.entities.Ruta;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.TipoNotificacion;
import com.uniride.mappers.ViajeMapper;
import com.uniride.repositories.PenalidadRepository;
import com.uniride.repositories.RutaRepository;
import com.uniride.repositories.SolicitudRepository;
import com.uniride.repositories.ViajeRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ViajeNotificacionUs25Test {
    @Mock ViajeRepository viajes;
    @Mock RutaRepository rutas;
    @Mock SolicitudRepository solicitudes;
    @Mock PenalidadRepository penalidades;
    @Mock ViajeMapper mapper;
    @Mock UsuarioService usuarioService;
    @Mock NotificacionService notificaciones;
    private ViajeService servicio;
    private Usuario conductor;
    private Ruta ruta;
    private Viaje viaje;

    @BeforeEach
    void preparar() {
        servicio = new ViajeService(viajes, rutas, solicitudes, penalidades,
                mapper, usuarioService, notificaciones);
        conductor = Usuario.builder().id(11L).nombre("Docente").build();
        ruta = Ruta.builder().id(12L).conductor(conductor).origen("Surco")
                .destino("UPC").estado(EstadoRuta.ACTIVA).build();
        LocalDateTime salida = LocalDateTime.now().plusHours(18);
        viaje = Viaje.builder().id(13L).ruta(ruta).estado(EstadoViaje.PROGRAMADO)
                .fecha(salida.toLocalDate()).hora(salida.toLocalTime())
                .dia("Lunes").build();
    }

    @Test
    void publicacionExitosaNotificaAlConductor() {
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(rutas.findByIdAndConductorId(12L, 11L)).thenReturn(Optional.of(ruta));
        when(mapper.toViaje(any(ViajeRequest.class))).thenReturn(viaje);
        when(viajes.save(any(Viaje.class))).thenAnswer(i -> i.getArgument(0));
        servicio.crear(12L, new ViajeRequest(viaje.getFecha(), viaje.getHora()));
        verify(notificaciones).notificar(eq(conductor), eq(TipoNotificacion.EXITO),
                eq("Viaje creado"), anyString());
    }

    @Test
    void confirmacionRepetidaAvisaSinCambiarViaje() {
        viaje.setConfirmado(true);
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(viajes.bloquearParaConductor(13L, 11L)).thenReturn(Optional.of(viaje));
        servicio.confirmar(13L);
        verify(notificaciones).notificar(eq(conductor), eq(TipoNotificacion.EXITO),
                eq("Viaje ya confirmado"), anyString());
        verify(viajes, never()).save(any(Viaje.class));
    }

    @Test
    void recordatorioEntreVeinteYDoceHorasSeEmiteUnaVez() {
        when(viajes.buscarPendientesDeConfirmacionAutomaticos(eq(EstadoViaje.PROGRAMADO),
                any(), any())).thenReturn(List.of(viaje));
        servicio.enviarRecordatoriosAutomaticos();
        servicio.enviarRecordatoriosAutomaticos();
        verify(viajes, times(1)).save(viaje);
        verify(notificaciones, times(1)).notificar(eq(conductor),
                eq(TipoNotificacion.RECORDATORIO), anyString(), anyString());
    }

    @Test
    void noEnviarRecordatorioAntesDeLaVentana() {
        LocalDateTime salida = LocalDateTime.now().plusHours(22);
        viaje.setFecha(salida.toLocalDate());
        viaje.setHora(salida.toLocalTime());
        when(viajes.buscarPendientesDeConfirmacionAutomaticos(eq(EstadoViaje.PROGRAMADO),
                any(), any())).thenReturn(List.of(viaje));
        servicio.enviarRecordatoriosAutomaticos();
        verify(viajes, never()).save(any(Viaje.class));
        verifyNoInteractions(notificaciones);
    }

    @Test
    void noEnviarRecordatorioDespuesDeVentana() {
        LocalDateTime salida = LocalDateTime.now().plusHours(10);
        viaje.setFecha(salida.toLocalDate());
        viaje.setHora(salida.toLocalTime());
        when(viajes.buscarPendientesDeConfirmacionAutomaticos(eq(EstadoViaje.PROGRAMADO),
                any(), any())).thenReturn(List.of(viaje));
        servicio.enviarRecordatoriosAutomaticos();
        verify(viajes, never()).save(any(Viaje.class));
        verifyNoInteractions(notificaciones);
    }
}
