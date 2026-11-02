package com.uniride.controllers;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.GlobalExceptionHandler;
import com.uniride.services.ContribucionService;
import com.uniride.services.MensajeService;
import com.uniride.services.RutaService;
import com.uniride.services.SolicitudService;
import com.uniride.services.ViajeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Verifica las validaciones HTTP y el parámetro confirmarImpacto de US07. */
class EdicionRutaViajeHttpTest {
    private final RutaService rutaService = mock(RutaService.class);
    private final ViajeService viajeService = mock(ViajeService.class);
    private MockMvc mvc;

    @BeforeEach
    void preparar() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new RutaController(rutaService),
                        new ViajeController(viajeService,
                                mock(SolicitudService.class), mock(ContribucionService.class)))
                .setControllerAdvice(new GlobalExceptionHandler(
                        new MensajeService(new StaticMessageSource())))
                .build();
    }

    private String bodyRuta() {
        return """
                {"origen":"Surco","capacidadMaxima":4,
                 "puntosReferencia":"Puente Primavera","zonasSinParada":"Ninguna"}
                """;
    }

    private String bodyViaje() {
        return """
                {"fecha":"2030-12-05","hora":"12:30"}
                """;
    }

    @Test
    void rutaSinConfirmacionSeDerivaAlServicio() throws Exception {
        mvc.perform(put("/rutas/5").contentType(MediaType.APPLICATION_JSON)
                        .content(bodyRuta()))
                .andExpect(status().isOk());
        verify(rutaService).actualizar(eq(5L), any(), eq(false));
    }

    @Test
    void rutaConConfirmacionSeDerivaAlServicio() throws Exception {
        mvc.perform(put("/rutas/5?confirmarImpacto=true")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyRuta()))
                .andExpect(status().isOk());
        verify(rutaService).actualizar(eq(5L), any(), eq(true));
    }

    @Test
    void rutaConFaltantesDa400() throws Exception {
        mvc.perform(put("/rutas/5").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"origen":"","capacidadMaxima":0,"puntosReferencia":""}
                                """))
                .andExpect(status().isBadRequest());
        verify(rutaService, never()).actualizar(anyLong(), any(), anyBoolean());
    }

    @Test
    void advertenciaRutaDa409() throws Exception {
        when(rutaService.actualizar(eq(5L), any(), eq(false)))
                .thenThrow(new BusinessException("Confirma usando ?confirmarImpacto=true"));
        mvc.perform(put("/rutas/5").contentType(MediaType.APPLICATION_JSON)
                        .content(bodyRuta()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value(
                        org.hamcrest.Matchers.containsString("confirmarImpacto")));
    }

    @Test
    void viajeSinConfirmacionSeDerivaAlServicio() throws Exception {
        mvc.perform(put("/viajes/7").contentType(MediaType.APPLICATION_JSON)
                        .content(bodyViaje()))
                .andExpect(status().isOk());
        verify(viajeService).actualizar(eq(7L), any(), eq(false));
    }

    @Test
    void viajeConConfirmacionSeDerivaAlServicio() throws Exception {
        mvc.perform(put("/viajes/7?confirmarImpacto=true")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyViaje()))
                .andExpect(status().isOk());
        verify(viajeService).actualizar(eq(7L), any(), eq(true));
    }

    @Test
    void viajeSinHoraDa400() throws Exception {
        mvc.perform(put("/viajes/7").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fecha\":\"2030-12-05\"}"))
                .andExpect(status().isBadRequest());
        verify(viajeService, never()).actualizar(anyLong(), any(), anyBoolean());
    }

    @Test
    void advertenciaViajeDa409() throws Exception {
        when(viajeService.actualizar(eq(7L), any(), eq(false)))
                .thenThrow(new BusinessException("Confirma usando ?confirmarImpacto=true"));
        mvc.perform(put("/viajes/7").contentType(MediaType.APPLICATION_JSON)
                        .content(bodyViaje()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value(
                        org.hamcrest.Matchers.containsString("confirmarImpacto")));
    }
}
