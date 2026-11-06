package com.uniride.controllers;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.uniride.exceptions.GlobalExceptionHandler;
import com.uniride.services.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PaginacionHardeningHttpTest {
    @ParameterizedTest
    @CsvSource({"/perfil/cursos", "/rutas", "/rutas/resumen", "/rutas/1/viajes",
            "/viajes/1/solicitudes", "/solicitudes"})
    void parametrosInvalidosSon400(String ruta) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(
                new HorarioAcademicoController(mock(HorarioAcademicoService.class, CALLS_REAL_METHODS)),
                new RutaController(mock(RutaService.class, CALLS_REAL_METHODS)),
                new ViajeController(mock(ViajeService.class, CALLS_REAL_METHODS),
                        mock(SolicitudService.class, CALLS_REAL_METHODS), mock(ContribucionService.class)),
                new SolicitudController(mock(SolicitudService.class, CALLS_REAL_METHODS)))
                .setControllerAdvice(new GlobalExceptionHandler(new MensajeService(new StaticMessageSource())))
                .build();
        for (String query : new String[]{"?page=-1", "?size=0", "?size=101"}) {
            mvc.perform(get(ruta + query)).andExpect(status().isBadRequest());
        }
    }
}
