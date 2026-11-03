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
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.SolicitudMapper;
import com.uniride.repositories.AcuerdoCompensacionRepository;
import com.uniride.repositories.MetodoCompensacionRepository;
import com.uniride.repositories.PenalidadRepository;
import com.uniride.repositories.PerfilRepository;
import com.uniride.repositories.SolicitudRepository;
import com.uniride.repositories.ViajeRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AceptacionConcurrenciaCuposServiceTest {
    @Mock SolicitudRepository solicitudes;
    @Mock ViajeRepository viajes;
    @Mock PerfilRepository perfiles;
    @Mock AcuerdoCompensacionRepository acuerdos;
    @Mock MetodoCompensacionRepository metodos;
    @Mock PenalidadRepository penalidades;
    @Mock SolicitudMapper mapper;
    @Mock UsuarioService usuarios;
    @Mock NotificacionService notificaciones;

    private SolicitudService servicio;
    private Usuario conductor;
    private Viaje viaje;
    private Solicitud solicitud;

    @BeforeEach
    void preparar() {
        servicio = new SolicitudService(solicitudes, viajes, perfiles, acuerdos,
                metodos, mapper, usuarios, notificaciones, penalidades);
        conductor = Usuario.builder().id(10L).nombre("Conductor").build();
        Usuario pasajero = Usuario.builder().id(11L).nombre("Pasajero").build();
        Ruta ruta = Ruta.builder().id(12L).conductor(conductor)
                .origen("Surco").destino("UPC").capacidadMaxima(1).build();
        viaje = Viaje.builder().id(13L).ruta(ruta).estado(EstadoViaje.PROGRAMADO)
                .fecha(LocalDate.now().plusDays(2)).hora(LocalTime.of(9, 0)).build();
        solicitud = Solicitud.builder().id(14L).viaje(viaje).pasajero(pasajero)
                .estado(EstadoSolicitud.PENDIENTE).build();
    }

    private void prepararConsultaYBloqueo() {
        when(usuarios.usuarioActual()).thenReturn(conductor);
        when(solicitudes.buscarViajeIdParaAceptar(14L, 10L)).thenReturn(Optional.of(13L));
        when(viajes.bloquearParaAceptar(13L)).thenReturn(Optional.of(viaje));
        when(solicitudes.findByIdAndViajeRutaConductorId(14L, 10L))
                .thenReturn(Optional.of(solicitud));
    }

    @Test
    void aceptaUltimoCupoDentroDelBloqueoYGeneraAcuerdo() {
        prepararConsultaYBloqueo();
        when(solicitudes.save(any(Solicitud.class))).thenAnswer(i -> i.getArgument(0));
        servicio.aceptar(14L);
        assertThat(viaje.getPasajeros()).isEqualTo(1);
        assertThat(solicitud.getEstado()).isEqualTo(EstadoSolicitud.ACEPTADA);
        InOrder orden = inOrder(solicitudes, viajes);
        orden.verify(solicitudes).buscarViajeIdParaAceptar(14L, 10L);
        orden.verify(viajes).bloquearParaAceptar(13L);
        orden.verify(solicitudes).findByIdAndViajeRutaConductorId(14L, 10L);
        verify(viajes).save(viaje);
        verify(acuerdos).save(any());
    }

    @Test
    void rechazaSegundoPasajeroSiUltimoCupoSeOcupo() {
        prepararConsultaYBloqueo();
        viaje.setPasajeros(1);
        assertThatThrownBy(() -> servicio.aceptar(14L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no tiene cupos");
        verify(viajes, never()).save(any());
        verify(solicitudes, never()).save(any());
        verifyNoInteractions(acuerdos);
    }

    @Test
    void repetirAceptacionNoIncrementaCuposNiCreaAcuerdo() {
        prepararConsultaYBloqueo();
        solicitud.setEstado(EstadoSolicitud.ACEPTADA);
        assertThatThrownBy(() -> servicio.aceptar(14L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("procesada");
        verify(notificaciones).notificarAccionYaRealizada(eq(conductor),
                eq("Solicitud ya aceptada"), anyString());
        verify(viajes, never()).save(any());
        verifyNoInteractions(acuerdos);
    }

    @Test
    void unaSolicitudAjenaNoPermiteBloquearViajes() {
        when(usuarios.usuarioActual()).thenReturn(conductor);
        when(solicitudes.buscarViajeIdParaAceptar(14L, 10L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> servicio.aceptar(14L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(viajes, never()).bloquearParaAceptar(anyLong());
        verifyNoInteractions(acuerdos);
    }

    @Test
    void viajeDesaparecidoDuranteLaEsperaNoAceptaSolicitud() {
        when(usuarios.usuarioActual()).thenReturn(conductor);
        when(solicitudes.buscarViajeIdParaAceptar(14L, 10L)).thenReturn(Optional.of(13L));
        when(viajes.bloquearParaAceptar(13L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> servicio.aceptar(14L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(solicitudes, never()).save(any());
        verifyNoInteractions(acuerdos);
    }

    @Test
    void viajeCanceladoNoAceptaAunqueHayaCupos() {
        prepararConsultaYBloqueo();
        viaje.setEstado(EstadoViaje.CANCELADO);
        assertThatThrownBy(() -> servicio.aceptar(14L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no está activo");
        verify(viajes, never()).save(any());
        verifyNoInteractions(acuerdos);
    }
}
