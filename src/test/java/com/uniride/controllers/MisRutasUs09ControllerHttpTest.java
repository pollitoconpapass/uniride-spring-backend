package com.uniride.controllers;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.uniride.dto.responses.RutaRespuesta;
import com.uniride.enums.EstadoRuta;
import com.uniride.services.RutaService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MisRutasUs09ControllerHttpTest {
    private final RutaService service = mock(RutaService.class);
    private MockMvc mvc;

    @BeforeEach
    void preparar() {
        mvc = MockMvcBuilders.standaloneSetup(new RutaController(service)).build();
    }

    @Test
    void listadoVacioMantienePageYAgregaCabeceraOrientativa() throws Exception {
        when(service.listarMisRutas(null, 0, 10)).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        mvc.perform(get("/rutas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(header().string("X-UniRide-Mensaje",
                        org.hamcrest.Matchers.containsString("Publica")));
    }

    @Test
    void resumenVacioIncluyeMensajeEnJson() throws Exception {
        when(service.listarMisRutas(null, 0, 10)).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        mvc.perform(get("/rutas/resumen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rutas.content.length()").value(0))
                .andExpect(jsonPath("$.mensaje", org.hamcrest.Matchers.containsString("Publica")));
    }

    @Test
    void filtroVacioExplicaElEstadoSolicitado() throws Exception {
        when(service.listarMisRutas(EstadoRuta.LLENA, 0, 10))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        mvc.perform(get("/rutas/resumen").param("estado", "LLENA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje", org.hamcrest.Matchers.containsString("LLENA")));
    }

    @Test
    void conservaParametrosDePaginacionYFiltrado() throws Exception {
        when(service.listarMisRutas(EstadoRuta.VENCIDA, 2, 5))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5), 0));
        mvc.perform(get("/rutas/resumen")
                        .param("estado", "VENCIDA").param("page", "2").param("size", "5"))
                .andExpect(status().isOk());
        verify(service).listarMisRutas(EstadoRuta.VENCIDA, 2, 5);
    }

    @Test
    void mantieneContratoLegacyCuandoHayRutas() throws Exception {
        RutaRespuesta ruta = new RutaRespuesta(1L, "Surco", "UPC", "Puente", null,
                null, 2, EstadoRuta.ACTIVA, null, 10L, "Conductor");
        when(service.listarMisRutas(null, 0, 10))
                .thenReturn(new PageImpl<>(List.of(ruta), PageRequest.of(0, 10), 1));
        mvc.perform(get("/rutas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].estado").value("ACTIVA"))
                .andExpect(header().doesNotExist("X-UniRide-Mensaje"));
    }
}
