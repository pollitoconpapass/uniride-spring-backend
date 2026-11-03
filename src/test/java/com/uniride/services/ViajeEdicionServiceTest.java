package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.uniride.dto.requests.ViajeRequest;
import com.uniride.entities.Ruta;
import com.uniride.entities.Solicitud;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.TipoNotificacion;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.ViajeMapper;
import com.uniride.repositories.PenalidadRepository;
import com.uniride.repositories.RutaRepository;
import com.uniride.repositories.SolicitudRepository;
import com.uniride.repositories.ViajeRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ViajeEdicionServiceTest {
    @Mock ViajeRepository viajeRepository;
    @Mock RutaRepository rutaRepository;
    @Mock SolicitudRepository solicitudRepository;
    @Mock PenalidadRepository penalidadRepository;
    @Mock ViajeMapper viajeMapper;
    @Mock UsuarioService usuarioService;
    @Mock NotificacionService notificacionService;
    private ViajeService servicio;
    private Usuario conductor;
    private Usuario pasajero;
    private Viaje viaje;

    @BeforeEach
    void preparar() {
        servicio = new ViajeService(viajeRepository, rutaRepository, solicitudRepository,
                penalidadRepository, viajeMapper, usuarioService, notificacionService);
        conductor = Usuario.builder().id(10L).nombre("Ana").build();
        pasajero = Usuario.builder().id(30L).nombre("Luis").build();
        Ruta ruta = Ruta.builder().id(1L).conductor(conductor).estado(EstadoRuta.ACTIVA).build();
        LocalDate fecha = LocalDate.now().plusDays(4);
        viaje = Viaje.builder().id(20L).ruta(ruta).fecha(fecha).hora(LocalTime.of(8, 0))
                .dia("Viernes").estado(EstadoViaje.PROGRAMADO).build();
        lenient().when(usuarioService.usuarioActual()).thenReturn(conductor);
        lenient().when(viajeRepository.bloquearParaConductor(20L, 10L))
                .thenReturn(Optional.of(viaje));
    }

    private ViajeRequest horarioNuevo() {
        return new ViajeRequest(LocalDate.now().plusDays(5), LocalTime.of(12, 0));
    }

    private void guardar() {
        when(viajeRepository.save(viaje)).thenReturn(viaje);
    }

    private void aceptado() {
        when(solicitudRepository.findByViajeIdAndEstadoIn(20L, List.of(EstadoSolicitud.ACEPTADA)))
                .thenReturn(List.of(Solicitud.builder().pasajero(pasajero).build()));
    }

    @Test
    void reprogramaSinPasajerosAceptados() {
        guardar();
        servicio.actualizar(20L, horarioNuevo(), false);
        assertThat(viaje.getFecha()).isEqualTo(LocalDate.now().plusDays(5));
        assertThat(viaje.getHora()).isEqualTo(LocalTime.NOON);
        verify(viajeRepository).save(viaje);
    }

    @Test
    void conAceptadosExigeConfirmacionSinCambiarFecha() {
        aceptado();
        LocalDate fechaOriginal = viaje.getFecha();
        assertThatThrownBy(() -> servicio.actualizar(20L, horarioNuevo(), false))
                .isInstanceOf(BusinessException.class).hasMessageContaining("?confirmarImpacto=true");
        assertThat(viaje.getFecha()).isEqualTo(fechaOriginal);
        verify(viajeRepository, never()).save(any());
    }

    @Test
    void confirmacionPermiteReprogramarYNotificaPasajero() {
        aceptado();
        guardar();
        servicio.actualizar(20L, horarioNuevo(), true);
        verify(notificacionService).notificar(eq(pasajero), eq(TipoNotificacion.ADVERTENCIA),
                eq("Horario de viaje modificado"), anyString());
        verify(viajeRepository).save(viaje);
    }

    @Test
    void viajeConfirmadoDebeReconfirmarseTrasReprogramacion() {
        viaje.setConfirmado(true);
        viaje.setFechaConfirmacion(LocalDateTime.now().minusDays(1));
        viaje.setRecordatorioEnviado(true);
        guardar();
        servicio.actualizar(20L, horarioNuevo(), true);
        assertThat(viaje.isConfirmado()).isFalse();
        assertThat(viaje.getFechaConfirmacion()).isNull();
        assertThat(viaje.isRecordatorioEnviado()).isFalse();
    }

    @Test
    void viajeConfirmadoNoPuedeReprogramarseADentroDe12Horas() {
        viaje.setConfirmado(true);
        LocalDateTime pronto = LocalDateTime.now().plusHours(5);
        assertThatThrownBy(() -> servicio.actualizar(20L,
                new ViajeRequest(pronto.toLocalDate(), pronto.toLocalTime()), true))
                .isInstanceOf(BusinessException.class).hasMessageContaining("12 horas");
    }

    @Test
    void fechaEnPasadoDa400() {
        assertThatThrownBy(() -> servicio.actualizar(20L,
                new ViajeRequest(LocalDate.now().minusDays(1), LocalTime.NOON), true))
                .isInstanceOf(CamposInvalidosException.class);
    }

    @Test
    void viajeYaEnProgresoNoSeEdita() {
        viaje.setEstado(EstadoViaje.EN_PROGRESO);
        assertThatThrownBy(() -> servicio.actualizar(20L, horarioNuevo(), true))
                .isInstanceOf(BusinessException.class).hasMessageContaining("PROGRAMADO");
    }

    @Test
    void noEditarViajeDeRutaEliminada() {
        viaje.getRuta().setEstado(EstadoRuta.ELIMINADA);
        assertThatThrownBy(() -> servicio.actualizar(20L, horarioNuevo(), true))
                .isInstanceOf(BusinessException.class).hasMessageContaining("eliminada");
    }

    @Test
    void horarioIgualNoGuardaNiNotifica() {
        servicio.actualizar(20L, new ViajeRequest(viaje.getFecha(), viaje.getHora()), false);
        verify(viajeRepository, never()).save(any());
        verify(notificacionService, never()).notificar(any(), any(), anyString(), anyString());
    }

    @Test
    void viajeAjenoNoSeEdita() {
        when(viajeRepository.bloquearParaConductor(99L, 10L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> servicio.actualizar(99L, horarioNuevo(), true))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void camposNulosDeServicioDan400() {
        assertThatThrownBy(() -> servicio.actualizar(20L,
                new ViajeRequest(null, null), true))
                .isInstanceOf(CamposInvalidosException.class);
    }
}
