package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uniride.entities.Ruta;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.TipoNotificacion;
import com.uniride.exceptions.BusinessException;
import com.uniride.mappers.RutaMapper;
import com.uniride.repositories.PerfilRepository;
import com.uniride.repositories.RutaRepository;
import com.uniride.repositories.SolicitudRepository;
import com.uniride.repositories.ViajeRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RutaServiceTest {
    @Mock RutaRepository rutaRepository;
    @Mock RutaMapper rutaMapper;
    @Mock UsuarioService usuarioService;
    @Mock PerfilRepository perfilRepository;
    @Mock NotificacionService notificacionService;
    @Mock ViajeRepository viajeRepository;
    @Mock SolicitudRepository solicitudRepository;
    @Mock ViajeService viajeService;
    @InjectMocks RutaService rutaService;

    private Ruta ruta;

    @BeforeEach
    void preparar() {
        Usuario conductor = Usuario.builder().id(10L).correoInstitucional("conductor@upc.edu.pe").build();
        ruta = Ruta.builder().id(1L).origen("Surco").destino("UPC")
                .estado(EstadoRuta.ACTIVA).conductor(conductor).build();
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(rutaRepository.findByIdAndConductorId(1L, 10L)).thenReturn(Optional.of(ruta));
    }

    private Viaje viaje(EstadoViaje estado, int horasRestantes) {
        LocalDateTime salida = LocalDateTime.now().plusHours(horasRestantes);
        return Viaje.builder().id(99L).ruta(ruta).estado(estado)
                .fecha(salida.toLocalDate()).hora(salida.toLocalTime()).build();
    }

    @Test
    void eliminaLogicamenteRutaSinViajes() {
        when(viajeRepository.findByRutaId(1L)).thenReturn(List.of());
        rutaService.eliminar(1L, false);
        assertThat(ruta.getEstado()).isEqualTo(EstadoRuta.ELIMINADA);
        verify(rutaRepository).save(ruta);
        verify(notificacionService).notificar(any(), eq(TipoNotificacion.EXITO),
                eq("Ruta eliminada"), anyString());
    }

    @Test
    void cancelaViajeFuturoSinAceptadosYArchivaRuta() {
        when(viajeRepository.findByRutaId(1L)).thenReturn(List.of(viaje(EstadoViaje.PROGRAMADO, 48)));
        rutaService.eliminar(1L, false);
        verify(viajeService).cancelar(99L);
        assertThat(ruta.getEstado()).isEqualTo(EstadoRuta.ELIMINADA);
    }

    @Test
    void exigeConfirmacionSiHayPasajeroAceptado() {
        when(viajeRepository.findByRutaId(1L)).thenReturn(List.of(viaje(EstadoViaje.PROGRAMADO, 48)));
        when(solicitudRepository.countByViajeIdAndEstado(99L, EstadoSolicitud.ACEPTADA)).thenReturn(1L);
        assertThatThrownBy(() -> rutaService.eliminar(1L, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("?confirmar=true");
        assertThat(ruta.getEstado()).isEqualTo(EstadoRuta.ACTIVA);
        verify(viajeService, never()).cancelar(99L);
    }

    @Test
    void confirmaCancelacionYArchivaRutaConAceptados() {
        when(viajeRepository.findByRutaId(1L)).thenReturn(List.of(viaje(EstadoViaje.PROGRAMADO, 48)));
        when(solicitudRepository.countByViajeIdAndEstado(99L, EstadoSolicitud.ACEPTADA)).thenReturn(1L);
        rutaService.eliminar(1L, true);
        verify(viajeService).cancelar(99L);
        assertThat(ruta.getEstado()).isEqualTo(EstadoRuta.ELIMINADA);
    }

    @Test
    void advierteCuandoFaltanMenosDeCuatroHoras() {
        when(viajeRepository.findByRutaId(1L)).thenReturn(List.of(viaje(EstadoViaje.PROGRAMADO, 2)));
        assertThatThrownBy(() -> rutaService.eliminar(1L, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("menos de 4 horas");
        verify(viajeService, never()).cancelar(99L);
    }

    @Test
    void impideEliminarRutaConViajeEnProgreso() {
        when(viajeRepository.findByRutaId(1L)).thenReturn(List.of(viaje(EstadoViaje.EN_PROGRESO, 2)));
        assertThatThrownBy(() -> rutaService.eliminar(1L, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("en progreso");
        assertThat(ruta.getEstado()).isEqualTo(EstadoRuta.ACTIVA);
    }

    @Test
    void siYaEstabaEliminadaNoDuplicaLaCancelacion() {
        ruta.setEstado(EstadoRuta.ELIMINADA);
        rutaService.eliminar(1L, true);
        verify(viajeRepository, never()).findByRutaId(1L);
        verify(notificacionService).notificar(any(), eq(TipoNotificacion.EXITO),
                eq("Ruta ya eliminada"), anyString());
    }
}
