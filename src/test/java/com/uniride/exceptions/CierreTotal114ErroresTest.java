package com.uniride.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.services.MensajeService;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.dao.DataIntegrityViolationException;

class CierreTotal114ErroresTest {
    @Test
    void respuesta500NoRevelaDetalleInternoNiContrasenas() {
        StaticMessageSource mensajes = new StaticMessageSource();
        mensajes.addMessage("error.inesperado", Locale.getDefault(), "Error inesperado: {0}");
        GlobalExceptionHandler handler = new GlobalExceptionHandler(new MensajeService(mensajes));
        var respuesta = handler.inesperada(new IllegalStateException("SELECT password FROM usuarios"));
        assertThat(respuesta.getStatusCode().value()).isEqualTo(500);
        assertThat(respuesta.getBody().mensaje()).doesNotContain("SELECT", "password", "usuarios");
    }

    @Test
    void violacionDeIntegridadDevuelve409SinDetallesSQL() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                new MensajeService(new StaticMessageSource()));
        var respuesta = handler.conflictoIntegridad(
                new DataIntegrityViolationException("duplicate key in method id = 9"));
        assertThat(respuesta.getStatusCode().value()).isEqualTo(409);
        assertThat(respuesta.getBody().mensaje()).doesNotContain("duplicate key", "id = 9");
    }
}
