package com.uniride.agent;

import com.uniride.agent.conversation.Aclaraciones;
import com.uniride.agent.conversation.IntencionAclarable;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.agent.conversation.Aclaraciones.Campo;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class IntencionAclarableTest {
    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneId.of("America/Lima"));

    @Test void nuevaRutaSoloReutilizaElOrigenCuandoSePide() {
        var previa = IntencionAclarable.inicioLocal("Crea una ruta con origen Surco, referencias Parque, capacidad 3", null, RELOJ);
        var nueva = IntencionAclarable.inicioLocal("Quiero crear otra", previa.contexto(), RELOJ);
        assertThat(nueva.argumentos()).isEmpty();
        var esquema = Aclaraciones.crear("crearRuta", Campo.ORIGEN.obligatorio(), Campo.REFERENCIAS.obligatorio(),
                Campo.CAPACIDAD.obligatorio());
        var aclarada = IntencionAclarable.continuar("Usa el mismo origen", nueva, esquema, previa.contexto(), RELOJ);
        assertThat(aclarada.argumentos()).containsOnlyKeys("origen").containsEntry("origen", "Surco");
    }

    @Test void conservaLaFechaDeUnViajeExplicitoParaCambiarSoloLaHora() {
        var previo = IntencionAclarable.inicioLocal("Crea un viaje con origen Surco, fecha 2026-12-01, hora ocho de la noche", null, RELOJ);
        var siguiente = IntencionAclarable.inicioLocal("Quiero cambiar la hora", previo.contexto(), RELOJ);
        assertThat(siguiente.argumentos()).containsEntry("fecha", "2026-12-01").containsEntry("hora", "20:00")
                .containsEntry("nuevaFecha", "2026-12-01").doesNotContainKey("nuevaHora");
    }
}
