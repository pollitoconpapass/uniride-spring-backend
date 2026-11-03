package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.uniride.dto.requests.RechazarRequest;
import com.uniride.entities.Ruta;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.EstadoViaje;
import com.uniride.exceptions.BusinessException;
import com.uniride.mappers.SolicitudMapper;
import com.uniride.repositories.*;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EstadosConcurrentesSolicitud113Test {
    @Mock SolicitudRepository solicitudes;
    @Mock ViajeRepository viajes;
    @Mock PerfilRepository perfiles;
    @Mock AcuerdoCompensacionRepository acuerdos;
    @Mock MetodoCompensacionRepository metodos;
    @Mock SolicitudMapper mapper;
    @Mock UsuarioService usuarios;
    @Mock NotificacionService notificaciones;
    @Mock PenalidadRepository penalidades;
    SolicitudService servicio;
    Usuario conductor;
    Usuario pasajero;
    Viaje viaje;
    Solicitud solicitud;

    @BeforeEach void preparar() {
        servicio = new SolicitudService(solicitudes, viajes, perfiles, acuerdos, metodos,
                mapper, usuarios, notificaciones, penalidades);
        conductor = Usuario.builder().id(1L).nombre("Conductor").build();
        pasajero = Usuario.builder().id(2L).nombre("Pasajero").build();
        LocalDateTime salida = LocalDateTime.now().plusHours(48);
        Ruta ruta = Ruta.builder().id(3L).conductor(conductor)
                .origen("Surco").destino("UPC").capacidadMaxima(2).estado(EstadoRuta.ACTIVA).build();
        viaje = Viaje.builder().id(4L).ruta(ruta).estado(EstadoViaje.PROGRAMADO)
                .fecha(salida.toLocalDate()).hora(salida.toLocalTime()).build();
        solicitud = Solicitud.builder().id(5L).viaje(viaje).pasajero(pasajero)
                .estado(EstadoSolicitud.PENDIENTE).build();
    }

    @Test void noCreaSolicitudSiRutaFueEliminada() {
        viaje.getRuta().setEstado(EstadoRuta.ELIMINADA);
        when(usuarios.usuarioActual()).thenReturn(pasajero);
        when(viajes.bloquearParaAceptar(4L)).thenReturn(Optional.of(viaje));
        assertThatThrownBy(() -> servicio.crear(4L, null)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("ruta eliminada");
        verify(solicitudes, never()).save(any(Solicitud.class));
    }

    @Test void noCreaSolicitudSiLaSalidaDeHoyYaOcurrio() {
        LocalDateTime pasado = LocalDateTime.now().minusHours(1);
        viaje.setFecha(pasado.toLocalDate());
        viaje.setHora(pasado.toLocalTime());
        when(usuarios.usuarioActual()).thenReturn(pasajero);
        when(viajes.bloquearParaAceptar(4L)).thenReturn(Optional.of(viaje));
        assertThatThrownBy(() -> servicio.crear(4L, null)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("ya ocurrió");
        verify(solicitudes, never()).save(any(Solicitud.class));
    }

    @Test void cancelacionDeSolicitudObtienePrimeroBloqueoDeViaje() {
        solicitud.setEstado(EstadoSolicitud.CANCELADA);
        when(usuarios.usuarioActual()).thenReturn(pasajero);
        when(solicitudes.buscarViajeIdParaCancelar(5L, 2L)).thenReturn(Optional.of(4L));
        when(viajes.bloquearParaAceptar(4L)).thenReturn(Optional.of(viaje));
        when(solicitudes.findByIdAndPasajeroId(5L, 2L)).thenReturn(Optional.of(solicitud));
        assertThatThrownBy(() -> servicio.cancelar(5L)).isInstanceOf(BusinessException.class);
        var order = inOrder(solicitudes, viajes);
        order.verify(solicitudes).buscarViajeIdParaCancelar(5L, 2L);
        order.verify(viajes).bloquearParaAceptar(4L);
        order.verify(solicitudes).findByIdAndPasajeroId(5L, 2L);
    }

    @Test void rechazoObtieneBloqueoAntesDeLeerEstadoDeSolicitud() {
        solicitud.setEstado(EstadoSolicitud.RECHAZADA);
        when(usuarios.usuarioActual()).thenReturn(conductor);
        when(solicitudes.buscarViajeIdParaAceptar(5L, 1L)).thenReturn(Optional.of(4L));
        when(viajes.bloquearParaAceptar(4L)).thenReturn(Optional.of(viaje));
        when(solicitudes.findByIdAndViajeRutaConductorId(5L, 1L)).thenReturn(Optional.of(solicitud));
        assertThatThrownBy(() -> servicio.rechazar(5L, new RechazarRequest("Motivo")))
                .isInstanceOf(BusinessException.class);
        var order = inOrder(solicitudes, viajes);
        order.verify(solicitudes).buscarViajeIdParaAceptar(5L, 1L);
        order.verify(viajes).bloquearParaAceptar(4L);
        order.verify(solicitudes).findByIdAndViajeRutaConductorId(5L, 1L);
    }

    @Test void aceptarNoProcesaSolicitudParaViajeDeHoyYaPasado() {
        LocalDateTime pasado = LocalDateTime.now().minusMinutes(5);
        viaje.setFecha(pasado.toLocalDate());
        viaje.setHora(pasado.toLocalTime());
        when(usuarios.usuarioActual()).thenReturn(conductor);
        when(solicitudes.buscarViajeIdParaAceptar(5L, 1L)).thenReturn(Optional.of(4L));
        when(viajes.bloquearParaAceptar(4L)).thenReturn(Optional.of(viaje));
        when(solicitudes.findByIdAndViajeRutaConductorId(5L, 1L))
                .thenReturn(Optional.of(solicitud));
        assertThatThrownBy(() -> servicio.aceptar(5L)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("ya ocurrió");
        verify(solicitudes, never()).save(any(Solicitud.class));
    }
}
