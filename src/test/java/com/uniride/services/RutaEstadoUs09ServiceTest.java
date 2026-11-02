package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.uniride.dto.responses.RutaRespuesta;
import com.uniride.entities.Ruta;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoViaje;
import com.uniride.mappers.RutaMapper;
import com.uniride.repositories.PerfilRepository;
import com.uniride.repositories.RutaRepository;
import com.uniride.repositories.SolicitudRepository;
import com.uniride.repositories.ViajeRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class RutaEstadoUs09ServiceTest {
    private final RutaRepository rutaRepository = mock(RutaRepository.class);
    private final RutaMapper rutaMapper = mock(RutaMapper.class);
    private final UsuarioService usuarioService = mock(UsuarioService.class);
    private final PerfilRepository perfilRepository = mock(PerfilRepository.class);
    private final NotificacionService notificacionService = mock(NotificacionService.class);
    private final ViajeRepository viajeRepository = mock(ViajeRepository.class);
    private final SolicitudRepository solicitudRepository = mock(SolicitudRepository.class);
    private final ViajeService viajeService = mock(ViajeService.class);
    private final RutaService service = new RutaService(rutaRepository, rutaMapper,
            usuarioService, perfilRepository, notificacionService, viajeRepository,
            solicitudRepository, viajeService);

    private Ruta ruta;

    @BeforeEach
    void preparar() {
        Usuario conductor = Usuario.builder().id(10L).nombre("Conductor").build();
        ruta = Ruta.builder().id(50L).conductor(conductor)
                .origen("Surco").destino("UPC")
                .capacidadMaxima(2).estado(EstadoRuta.ACTIVA).build();
        when(usuarioService.usuarioActual()).thenReturn(conductor);
        when(rutaRepository.findByConductorId(10L)).thenReturn(List.of(ruta));
        when(viajeRepository.buscarTodosPorConductor(10L)).thenReturn(List.of());
        when(rutaRepository.findByConductorIdAndEstadoNot(eq(10L),
                eq(EstadoRuta.ELIMINADA), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ruta)));
    }

    private Viaje viaje(EstadoViaje estado, int dias, int pasajeros) {
        LocalDateTime salida = LocalDateTime.now().plusDays(dias).withHour(20).withMinute(0);
        return Viaje.builder().id((long) dias + 1000L).ruta(ruta).estado(estado)
                .fecha(salida.toLocalDate()).hora(salida.toLocalTime())
                .pasajeros(pasajeros).build();
    }

    @Test
    void sinViajesConservaEstadoActivo() {
        service.listarMisRutas(null, 0, 10);
        assertThat(ruta.getEstado()).isEqualTo(EstadoRuta.ACTIVA);
        verify(rutaRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void rutaConTodosLosViajesFuturosLlenosPasaALlena() {
        when(viajeRepository.buscarTodosPorConductor(10L))
                .thenReturn(List.of(viaje(EstadoViaje.PROGRAMADO, 7, 2)));
        service.listarMisRutas(null, 0, 10);
        assertThat(ruta.getEstado()).isEqualTo(EstadoRuta.LLENA);
        verify(rutaRepository).saveAllAndFlush(List.of(ruta));
    }

    @Test
    void rutaConUnViajeLlenoYOtroDisponiblePermaneceActiva() {
        when(viajeRepository.buscarTodosPorConductor(10L))
                .thenReturn(List.of(viaje(EstadoViaje.PROGRAMADO, 7, 2),
                        viaje(EstadoViaje.PROGRAMADO, 8, 1)));
        service.listarMisRutas(null, 0, 10);
        assertThat(ruta.getEstado()).isEqualTo(EstadoRuta.ACTIVA);
        verify(rutaRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void historicoSinViajesFuturosPasaAVencida() {
        when(viajeRepository.buscarTodosPorConductor(10L))
                .thenReturn(List.of(viaje(EstadoViaje.COMPLETADO, -3, 2)));
        service.listarMisRutas(null, 0, 10);
        assertThat(ruta.getEstado()).isEqualTo(EstadoRuta.VENCIDA);
        verify(rutaRepository).saveAllAndFlush(List.of(ruta));
    }

    @Test
    void viajeProgramadoEnFechaPasadaNoMantieneActiva() {
        when(viajeRepository.buscarTodosPorConductor(10L))
                .thenReturn(List.of(viaje(EstadoViaje.PROGRAMADO, -3, 0)));
        service.listarMisRutas(null, 0, 10);
        assertThat(ruta.getEstado()).isEqualTo(EstadoRuta.VENCIDA);
    }

    @Test
    void rutaLlenaSeReactivaCuandoHayCupo() {
        ruta.setEstado(EstadoRuta.LLENA);
        when(viajeRepository.buscarTodosPorConductor(10L))
                .thenReturn(List.of(viaje(EstadoViaje.PROGRAMADO, 7, 1)));
        service.listarMisRutas(null, 0, 10);
        assertThat(ruta.getEstado()).isEqualTo(EstadoRuta.ACTIVA);
        verify(rutaRepository).saveAllAndFlush(List.of(ruta));
    }

    @Test
    void rutaVencidaSeReactivaSiApareceViajeProgramadoFuturo() {
        ruta.setEstado(EstadoRuta.VENCIDA);
        when(viajeRepository.buscarTodosPorConductor(10L))
                .thenReturn(List.of(viaje(EstadoViaje.PROGRAMADO, 7, 0)));
        service.listarMisRutas(null, 0, 10);
        assertThat(ruta.getEstado()).isEqualTo(EstadoRuta.ACTIVA);
    }

    @Test
    void nuncaResucitaUnaRutaEliminada() {
        ruta.setEstado(EstadoRuta.ELIMINADA);
        when(viajeRepository.buscarTodosPorConductor(10L))
                .thenReturn(List.of(viaje(EstadoViaje.PROGRAMADO, 7, 0)));
        service.listarMisRutas(null, 0, 10);
        assertThat(ruta.getEstado()).isEqualTo(EstadoRuta.ELIMINADA);
        verify(rutaRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void filtraEstadoLlenaDespuesDeSincronizar() {
        when(viajeRepository.buscarTodosPorConductor(10L))
                .thenReturn(List.of(viaje(EstadoViaje.PROGRAMADO, 7, 2)));
        when(rutaRepository.buscarPorConductorYEstado(eq(10L), eq(EstadoRuta.LLENA),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of(ruta)));
        service.listarMisRutas(EstadoRuta.LLENA, 0, 5);
        assertThat(ruta.getEstado()).isEqualTo(EstadoRuta.LLENA);
        verify(rutaRepository).buscarPorConductorYEstado(eq(10L), eq(EstadoRuta.LLENA), any());
    }

    @Test
    void permiteFiltroEliminadaParaConsultarHistorial() {
        ruta.setEstado(EstadoRuta.ELIMINADA);
        when(rutaRepository.buscarPorConductorYEstado(eq(10L), eq(EstadoRuta.ELIMINADA),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of(ruta)));
        service.listarMisRutas(EstadoRuta.ELIMINADA, 0, 10);
        verify(rutaRepository).buscarPorConductorYEstado(eq(10L), eq(EstadoRuta.ELIMINADA), any());
    }

    @Test
    void sinRutasNoConsultaViajesNiEscribeDatos() {
        when(rutaRepository.findByConductorId(10L)).thenReturn(List.of());
        service.listarMisRutas(null, 0, 10);
        verify(viajeRepository, never()).buscarTodosPorConductor(any());
        verify(rutaRepository, never()).saveAllAndFlush(anyList());
    }
}
