package com.uniride.controllers;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.uniride.dto.responses.*;
import com.uniride.enums.*;
import com.uniride.services.SolicitudesRecibidasUs14Service;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SolicitudesRecibidasUs14ControllerHttpTest {
    private final SolicitudesRecibidasUs14Service service = mock(SolicitudesRecibidasUs14Service.class);
    private MockMvc mvc;

    @BeforeEach
    void preparar() {
        mvc = MockMvcBuilders.standaloneSetup(new SolicitudesRecibidasUs14Controller(service))
                .build();
    }

    @Test
    void sinRutasDevuelve200ConMensajeEnJson() throws Exception {
        when(service.porRuta(null, 0, 10)).thenReturn(
                new SolicitudesRecibidasUs14ResumenRespuesta(
                        new PageImpl<>(List.of(), PageRequest.of(0, 10), 0),
                        "Aun no tienes rutas publicadas."));
        mvc.perform(get("/solicitudes/recibidas/por-ruta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rutas.content.length()").value(0))
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("rutas")));
    }

    @Test
    void rutaSinSolicitudesIncluyeRecomendacion() throws Exception {
        RutaSolicitudesUs14Respuesta r = new RutaSolicitudesUs14Respuesta(
                3L, "Surco", "UPC", EstadoRuta.ACTIVA, List.of(),
                "Aun no hay interesados. Revisa los detalles.");
        when(service.porRuta(null, 0, 10)).thenReturn(
                new SolicitudesRecibidasUs14ResumenRespuesta(
                        new PageImpl<>(List.of(r), PageRequest.of(0, 10), 1), null));
        mvc.perform(get("/solicitudes/recibidas/por-ruta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rutas.content[0].rutaId").value(3))
                .andExpect(jsonPath("$.rutas.content[0].solicitudes.length()").value(0))
                .andExpect(jsonPath("$.rutas.content[0].mensaje").value(
                        org.hamcrest.Matchers.containsString("interesados")));
    }

    @Test
    void respetaFiltroYPaginacion() throws Exception {
        when(service.porRuta(EstadoSolicitud.PENDIENTE, 2, 5)).thenReturn(
                new SolicitudesRecibidasUs14ResumenRespuesta(
                        new PageImpl<>(List.of(), PageRequest.of(2, 5), 0), null));
        mvc.perform(get("/solicitudes/recibidas/por-ruta")
                        .param("estado", "PENDIENTE")
                        .param("page", "2").param("size", "5"))
                .andExpect(status().isOk());
        verify(service).porRuta(EstadoSolicitud.PENDIENTE, 2, 5);
    }

    @Test
    void detalleMuestraPerfilYMensaje() throws Exception {
        PerfilPasajeroUs14Respuesta perfil = new PerfilPasajeroUs14Respuesta(
                5L, "Ana", "Lopez", "Ingenieria de Software", "Surco", "UPC", null, null);
        when(service.detalle(17L)).thenReturn(new DetalleSolicitudRecibidaUs14Respuesta(
                17L, 1L, 12L, EstadoSolicitud.PENDIENTE, "Puedo llegar antes", perfil, null));
        mvc.perform(get("/solicitudes/recibidas/17/detalle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solicitudId").value(17))
                .andExpect(jsonPath("$.mensajeSolicitud").value("Puedo llegar antes"))
                .andExpect(jsonPath("$.pasajero.carrera").value("Ingenieria de Software"))
                .andExpect(jsonPath("$.pasajero.telefono").doesNotExist());
    }
}
