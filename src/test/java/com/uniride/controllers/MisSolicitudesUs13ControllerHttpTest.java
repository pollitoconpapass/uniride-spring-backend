package com.uniride.controllers;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.uniride.dto.responses.*;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.services.SolicitudService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MisSolicitudesUs13ControllerHttpTest {
    private final SolicitudService service = mock(SolicitudService.class);
    private MockMvc mvc;

    @BeforeEach
    void preparar() {
        mvc = MockMvcBuilders.standaloneSetup(new SolicitudController(service)).build();
    }

    private Page<SolicitudRespuesta> vacia() {
        return new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
    }

    @Test
    void listadoVacioDevuelve200YCabeceraOrientativa() throws Exception {
        when(service.listarMisSolicitudes(null, 0, 10)).thenReturn(vacia());
        mvc.perform(get("/solicitudes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(header().string("X-UniRide-Mensaje",
                        org.hamcrest.Matchers.containsString("no tienes solicitudes")));
    }

    @Test
    void resumenVacioIncluyeMensajeEnJson() throws Exception {
        when(service.listarMisSolicitudes(null, 0, 10)).thenReturn(vacia());
        mvc.perform(get("/solicitudes/resumen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solicitudes.content.length()").value(0))
                .andExpect(jsonPath("$.mensaje", org.hamcrest.Matchers.containsString("no tienes")));
    }

    @Test
    void filtroSinCoincidenciasMuestraEstado() throws Exception {
        when(service.listarMisSolicitudes(EstadoSolicitud.ACEPTADA, 0, 10)).thenReturn(vacia());
        mvc.perform(get("/solicitudes/resumen").param("estado", "ACEPTADA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje", org.hamcrest.Matchers.containsString("ACEPTADA")));
    }

    @Test
    void listadoExistenteMantienePageOriginalYEstado() throws Exception {
        SolicitudRespuesta r = new SolicitudRespuesta(
                5L, 9L, null, null, null, 4L, "Surco", "UPC", null,
                null, EstadoSolicitud.PENDIENTE, null, null, null, 7L,
                "Ana", "ana@upc.edu.pe", null, null, null, null, null, null, null);
        when(service.listarMisSolicitudes(null, 0, 10))
                .thenReturn(new PageImpl<>(List.of(r), PageRequest.of(0, 10), 1));
        mvc.perform(get("/solicitudes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].estado").value("PENDIENTE"))
                .andExpect(header().doesNotExist("X-UniRide-Mensaje"));
    }

    @Test
    void conservaParametrosDePaginacion() throws Exception {
        when(service.listarMisSolicitudes(EstadoSolicitud.RECHAZADA, 2, 5))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5), 0));
        mvc.perform(get("/solicitudes/resumen")
                .param("estado", "RECHAZADA").param("page", "2").param("size", "5"))
                .andExpect(status().isOk());
        verify(service).listarMisSolicitudes(EstadoSolicitud.RECHAZADA, 2, 5);
    }

    @Test
    void historialVacioDevuelveMensajeEnJson() throws Exception {
        SolicitudesHistorialResumenRespuesta r = new SolicitudesHistorialResumenRespuesta(
                new PageImpl<>(List.of(), PageRequest.of(0, 10), 0),
                "Aun no tienes solicitudes en tu historial.");
        when(service.historialMisSolicitudes(0, 10)).thenReturn(r);
        mvc.perform(get("/solicitudes/historial"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solicitudes.content.length()").value(0))
                .andExpect(jsonPath("$.mensaje", org.hamcrest.Matchers.containsString("historial")));
    }

    @Test
    void historialTieneSoloLecturaYPenalidades() throws Exception {
        SolicitudHistorialRespuesta item = new SolicitudHistorialRespuesta(null,
                com.uniride.enums.EstadoViaje.CANCELADO, true,
                List.of(new PenalidadHistorialRespuesta(33L,
                        com.uniride.enums.TipoPenalidad.LEVE, "Cancelacion", null)));
        when(service.historialMisSolicitudes(0, 10)).thenReturn(
                new SolicitudesHistorialResumenRespuesta(
                        new PageImpl<>(List.of(item), PageRequest.of(0, 10), 1), null));
        mvc.perform(get("/solicitudes/historial"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solicitudes.content[0].soloLectura").value(true))
                .andExpect(jsonPath("$.solicitudes.content[0].estadoViaje").value("CANCELADO"))
                .andExpect(jsonPath("$.solicitudes.content[0].penalidades[0].tipo").value("LEVE"));
    }
}
