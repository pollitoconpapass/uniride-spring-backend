package com.uniride.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.uniride.dto.responses.BusquedaRespuesta;
import com.uniride.entities.Perfil;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoViaje;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.BusquedaMapper;
import com.uniride.repositories.PerfilRepository;
import com.uniride.repositories.ViajeRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class BusquedaUs10ServiceTest {
    @Mock ViajeRepository viajeRepository;
    @Mock PerfilRepository perfilRepository;
    @Mock UsuarioService usuarioService;
    @Mock BusquedaMapper busquedaMapper;
    private BusquedaService servicio;
    private Usuario pasajero;

    @BeforeEach
    void setup() {
        servicio = new BusquedaService(viajeRepository, perfilRepository, usuarioService, busquedaMapper);
        pasajero = Usuario.builder().id(20L).build();
    }

    @Test
    void normalizaFiltrosYConsultaRankingAntesDePaginar() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        when(viajeRepository.buscarViajesRelevantes(nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(LocalTime.class), nullable(String.class), any(), any(),
                any(), any(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 5), 0));
        servicio.buscar("  SAN BORJA  ", " UPC ", " LUNES ", LocalTime.of(8, 0), false, 0, 5);
        verify(viajeRepository).buscarViajesRelevantes(eq("san borja"), eq("upc"), eq("lunes"),
                eq(LocalTime.of(8, 0)), isNull(), eq(EstadoViaje.PROGRAMADO),
                eq(EstadoRuta.ELIMINADA), any(LocalDate.class), any(LocalTime.class),
                eq(PageRequest.of(0, 5)));
    }

    @Test
    void sinFiltrosUsaValoresNulosYConservaPaginacion() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        when(viajeRepository.buscarViajesRelevantes(nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(LocalTime.class), nullable(String.class), any(), any(),
                any(), any(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 10), 0));
        assertThat(servicio.buscar(" ", null, "", null, false, 1, 10)).isEmpty();
        verify(viajeRepository).buscarViajesRelevantes(isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(EstadoViaje.PROGRAMADO), eq(EstadoRuta.ELIMINADA), any(), any(),
                eq(PageRequest.of(1, 10)));
    }

    @Test
    void cercaniaRequierePerfil() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        when(perfilRepository.findByUsuarioId(20L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> servicio.buscar(null, null, null, null, true, 0, 10))
                .isInstanceOf(ResourceNotFoundException.class).hasMessageContaining("perfil");
        verifyNoInteractions(viajeRepository);
    }

    @Test
    void cercaniaConDistritoVacioDa400() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        when(perfilRepository.findByUsuarioId(20L)).thenReturn(Optional.of(Perfil.builder().distrito(" ").build()));
        assertThatThrownBy(() -> servicio.buscar(null, null, null, null, true, 0, 10))
                .isInstanceOf(CamposInvalidosException.class).hasMessageContaining("distrito");
    }

    @Test
    void cercaniaUsaDistritoNormalizado() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        when(perfilRepository.findByUsuarioId(20L)).thenReturn(Optional.of(Perfil.builder().distrito(" Santa Anita ").build()));
        when(viajeRepository.buscarViajesRelevantes(nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(LocalTime.class), nullable(String.class), any(), any(),
                any(), any(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        servicio.buscar(null, null, null, null, true, 0, 10);
        verify(viajeRepository).buscarViajesRelevantes(isNull(), isNull(), isNull(), isNull(),
                eq("santa anita"), eq(EstadoViaje.PROGRAMADO), eq(EstadoRuta.ELIMINADA),
                any(), any(), eq(PageRequest.of(0, 10)));
    }

    @Test
    void paginaNegativaDa400SinConsultarRepositorios() {
        assertThatThrownBy(() -> servicio.buscar(null, null, null, null, false, -1, 10))
                .isInstanceOf(CamposInvalidosException.class);
        verifyNoInteractions(viajeRepository);
    }

    @Test
    void limiteDePaginacionDa400() {
        assertThatThrownBy(() -> servicio.buscar(null, null, null, null, false, 0, 101))
                .isInstanceOf(CamposInvalidosException.class);
    }

    @Test
    void paginaConResultadosConservaMapper() {
        when(usuarioService.usuarioActual()).thenReturn(pasajero);
        Viaje viaje = Viaje.builder().id(4L).build();
        BusquedaRespuesta dto = mock(BusquedaRespuesta.class);
        when(viajeRepository.buscarViajesRelevantes(nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(LocalTime.class), nullable(String.class), any(), any(),
                any(), any(), any())).thenReturn(new PageImpl<>(List.of(viaje), PageRequest.of(0, 10), 1));
        when(busquedaMapper.toBusquedaRespuesta(viaje)).thenReturn(dto);
        assertThat(servicio.buscar(null, null, null, null, false, 0, 10).getContent()).containsExactly(dto);
    }
}
