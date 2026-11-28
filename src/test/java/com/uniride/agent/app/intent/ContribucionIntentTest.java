package com.uniride.agent.app.intent;

import static org.assertj.core.api.Assertions.assertThat;
import com.uniride.agent.app.dto.ContribucionFiltros;
import com.uniride.agent.app.dto.ContribucionResultado.Estado;
import com.uniride.config.TiempoConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContribucionIntentTest {
    private final Clock lima = Clock.fixed(Instant.parse("2026-11-22T01:00:00Z"), TiempoConfig.LIMA);

    @Test void obtieneRutaLiteralYLaHoraHabladaUsandoLaFechaLocal() {
        var consulta = clasificar("¿Cuánto cuesta el viaje de Jesús María a la UPC del 25 de noviembre "
                + "a las ocho de la mañana con dos pasajeros?");

        assertThat(consulta.aclaracion()).isNull();
        assertThat(consulta.filtros()).isEqualTo(new ContribucionFiltros("Jesús María", "UPC", null,
                LocalDate.of(2026, 11, 25), LocalTime.of(8, 0), 2, null, false));
        assertThat(clasificar("¿Cuánto cuesta el viaje de mañana a las ocho de la noche?").filtros())
                .extracting(ContribucionFiltros::fecha, ContribucionFiltros::hora)
                .containsExactly(LocalDate.of(2026, 11, 22), LocalTime.of(20, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"¿Cuánto aporto por el viaje el 25 a las 8 con 2 pasajeros?",
            "¿Cuánto aporto por el viaje del próximo viernes?",
            "¿Cuánto aporto por el viaje del 25 de noviembre a las 08:100?",
            "¿Cuánto aporto por el viaje del 25 de noviembre a las ocho menos cuarto?",
            "¿Cuánto aporto por el viaje del 25 o 26 de noviembre?"})
    void noDescartaFiltrosIncompletosParaElegirOtroViaje(String message) {
        assertThat(clasificar(message).aclaracion()).isNotBlank();
    }

    @Test void retomaLaRutaEnLaAclaracionYLaVuelveAConsultarEnUnaPreguntaSobreFavor() {
        var ruta = clasificar("¿Cuánto tendría que aportar por un viaje de Surco a la UPC?").filtros();
        var fecha = ContribucionIntent.classify("Al del 25 de noviembre a las 8.",
                new ContribucionIntent.Borrador(ruta, Estado.AMBIGUA), lima);
        var cantidad = ContribucionIntent.classify("Somos tres pasajeros.",
                new ContribucionIntent.Borrador(fecha.filtros(), Estado.FALTAN_PASAJEROS), lima);
        var favor = ContribucionIntent.classify("¿Qué favor podría ofrecer como compensación?",
                new ContribucionIntent.Borrador(cantidad.filtros(), Estado.SUGERIDA), lima);

        assertThat(favor.filtros()).isEqualTo(new ContribucionFiltros("Surco", "UPC", null,
                LocalDate.of(2026, 11, 25), LocalTime.of(8, 0), 3, null, false));
    }

    @Test void noTrasladaFechaHoraOCantidadAlConsultarOtraRuta() {
        var anterior = new ContribucionIntent.Borrador(new ContribucionFiltros("Surco", "UPC", null,
                LocalDate.of(2026, 11, 25), LocalTime.of(8, 0), 3, null, false), Estado.SUGERIDA);
        var siguiente = ContribucionIntent.classify("¿Cuánta gasolina para un viaje de Miraflores a la UPC?", anterior, lima);

        assertThat(siguiente.filtros()).isEqualTo(new ContribucionFiltros("Miraflores", "UPC",
                null, null, null, null, null, false));
        assertThat(ContribucionIntent.classify("Registra un método FAVOR para compensación", anterior, lima)).isNull();
        assertThat(ContribucionIntent.classify("Quiero solicitar un cupo para ese viaje", anterior, lima)).isNull();
    }

    private ContribucionIntent.Solicitud clasificar(String message) {
        return ContribucionIntent.classify(message, null, lima);
    }
}
