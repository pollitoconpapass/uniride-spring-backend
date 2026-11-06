package com.uniride.controllers;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.uniride.exceptions.GlobalExceptionHandler;
import com.uniride.services.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ErroresProtocoloHttpTest {
    @Test void erroresDeProtocoloNoSeConviertenEn500() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new HorarioAcademicoController(mock(HorarioAcademicoService.class)))
                .setControllerAdvice(new GlobalExceptionHandler(new MensajeService(new StaticMessageSource()))).build();
        mvc.perform(post("/perfil/horarios/archivo")).andExpect(status().isBadRequest());
        mvc.perform(put("/perfil/cursos")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/perfil/cursos").contentType(MediaType.TEXT_PLAIN).content("texto"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(get("/inexistente")).andExpect(status().isNotFound());
    }
}
