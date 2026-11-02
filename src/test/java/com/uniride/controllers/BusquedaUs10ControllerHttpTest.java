package com.uniride.controllers;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.uniride.dto.responses.BusquedaRespuesta;
import com.uniride.services.BusquedaService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BusquedaUs10ControllerHttpTest {
    BusquedaService servicio = mock(BusquedaService.class);
    MockMvc mvc;

    @BeforeEach
    void preparar() { mvc = MockMvcBuilders.standaloneSetup(new BusquedaController(servicio)).build(); }

    @Test
    void sinViajesDevuelve200YOrientacionEnCabecera() throws Exception {
        when(servicio.buscar(nullable(String.class), nullable(String.class), nullable(String.class),
                        nullable(java.time.LocalTime.class), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        mvc.perform(get("/viajes/buscar"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-UniRide-Mensaje", org.hamcrest.Matchers.containsString("No se encontraron")))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void resumenVacioExplicaQueAmpliar() throws Exception {
        when(servicio.buscar(nullable(String.class), nullable(String.class), nullable(String.class),
                        nullable(java.time.LocalTime.class), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        mvc.perform(get("/viajes/buscar/resumen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("No se encontraron")))
                .andExpect(jsonPath("$.sugerencia").value(org.hamcrest.Matchers.containsString("ampliar")));
    }

    @Test
    void filtroSeEntregaAlServicio() throws Exception {
        when(servicio.buscar(nullable(String.class), nullable(String.class), nullable(String.class),
                        nullable(java.time.LocalTime.class), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 5), 0));
        mvc.perform(get("/viajes/buscar").param("origen", "Surco").param("destino", "UPC")
                .param("dia", "Lunes").param("cercania", "true")
                .param("page", "1").param("size", "5"))
                .andExpect(status().isOk());
        verify(servicio).buscar(eq("Surco"), eq("UPC"), eq("Lunes"), isNull(), eq(true), eq(1), eq(5));
    }

    @Test
    void cuandoNoHayResultadosEnUnaPaginaOrientarAPaginaAnterior() throws Exception {
        when(servicio.buscar(nullable(String.class), nullable(String.class), nullable(String.class),
                        nullable(java.time.LocalTime.class), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(3, 10), 12));
        mvc.perform(get("/viajes/buscar/resumen").param("page", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("página anterior")))
                .andExpect(jsonPath("$.sugerencia").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void cuandoHayViajesConservaContratoPage() throws Exception {
        BusquedaRespuesta viaje = new BusquedaRespuesta(1L, java.time.LocalDate.now().plusDays(2),
                java.time.LocalTime.of(8, 0), "Lunes", 3L, "Surco", "UPC", "Puente", null,
                null, 3, 5L, "Carlos", null);
        when(servicio.buscar(nullable(String.class), nullable(String.class), nullable(String.class),
                        nullable(java.time.LocalTime.class), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(viaje), PageRequest.of(0, 10), 1));
        mvc.perform(get("/viajes/buscar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].origen").value("Surco"));
    }
}
