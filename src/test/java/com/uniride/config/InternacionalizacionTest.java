package com.uniride.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.uniride.services.MensajeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class InternacionalizacionTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private MensajeService mensajeService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @DisplayName("MensajeService debe resolver mensajes en español latinoamericano (es_419) y en inglés (en_US)")
    void mensajeServiceDebeResolverMensajesEnAmbosIdiomas() {
        // Validación de español latinoamericano (es_419)
        assertThat(mensajeService.obtenerMensaje("error.validacion", I18nConfig.LOCALE_ES_419))
                .isEqualTo("Errores de validación");
        assertThat(mensajeService.obtenerMensaje("auth.login_exitoso", I18nConfig.LOCALE_ES_419))
                .isEqualTo("Inicio de sesión exitoso");
        assertThat(mensajeService.obtenerMensaje("auth.error.credenciales_incorrectas", I18nConfig.LOCALE_ES_419))
                .isEqualTo("Correo o contraseña incorrectos");
        assertThat(mensajeService.obtenerMensaje("error.archivo_demasiado_grande", I18nConfig.LOCALE_ES_419))
                .isEqualTo("El archivo no puede superar un tamaño de 1 MB");

        // Validación de inglés estadounidense (en_US)
        assertThat(mensajeService.obtenerMensaje("error.validacion", I18nConfig.LOCALE_EN_US))
                .isEqualTo("Validation errors");
        assertThat(mensajeService.obtenerMensaje("auth.login_exitoso", I18nConfig.LOCALE_EN_US))
                .isEqualTo("Login successful");
        assertThat(mensajeService.obtenerMensaje("auth.error.credenciales_incorrectas", I18nConfig.LOCALE_EN_US))
                .isEqualTo("Incorrect email or password");
        assertThat(mensajeService.obtenerMensaje("error.archivo_demasiado_grande", I18nConfig.LOCALE_EN_US))
                .isEqualTo("The file cannot exceed 1 MB");
    }

    @Test
    @DisplayName("Endpoint debe responder error de validación en español con Accept-Language: es-419 o por defecto")
    void debeResponderErrorDeValidacionEnEspanolPorDefecto() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .header("Accept-Language", "es-419")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("Errores de validación"));
    }

    @Test
    @DisplayName("Endpoint debe responder error de validación en inglés con Accept-Language: en-US")
    void debeResponderErrorDeValidacionEnIngles() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .header("Accept-Language", "en-US")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("Validation errors"));
    }

    @Test
    @DisplayName("Endpoint de login debe responder credenciales incorrectas en español e inglés según Accept-Language")
    void debeResponderCredencialesIncorrectasEnAmbosIdiomas() throws Exception {
        String body = "{\"correo\":\"noexiste@upc.edu.pe\",\"contrasena\":\"Clave123!\"}";

        // En español (es-419)
        mockMvc.perform(post("/auth/login")
                        .header("Accept-Language", "es-419")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.mensaje").value("Correo o contraseña incorrectos"));

        // En inglés (en-US)
        mockMvc.perform(post("/auth/login")
                        .header("Accept-Language", "en-US")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.mensaje").value("Incorrect email or password"));
    }
}
