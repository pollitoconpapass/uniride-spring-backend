package com.uniride.agent.app.intent;

import static org.assertj.core.api.Assertions.assertThat;
import com.uniride.agent.app.clarification.IntencionAclarable;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Un mensaje de otro dominio no puede ser reclamado por el clasificador de compensación. */
class AgentDomainGuardTest {
    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneId.of("America/Lima"));
    private static final String HORARIO =
            "Puedes actualizar mi horario y poner que tengo cálculo 3 a las 7 de la mañana los viernes.";

    @ParameterizedTest
    @ValueSource(strings = {
            "Puedes actualizar mi horario y poner que tengo cálculo 3 a las 7 de la mañana los viernes.",
            "Vale, y ahora puedes actualizar mi horario y poner que tengo cálculo 3 los viernes a las 7.",
            "Quiero cambiar mis gustos y poner que me gusta el fútbol.",
            "Actualiza mi perfil y pon que estudio en la UPC.",
            "Cámbiame el rol a conductor.",
            "Agrega Cálculo 3 los viernes de 7 a 9.",
            "Muéstrame mi horario del lunes.",
            "Quiero actualizar mis días disponibles."
    })
    void otherDomainsAreNotClaimedByCompensation(String mensaje) {
        var decision = CompensationIntent.classify(mensaje, null);
        assertThat(decision.message()).isNull();
        assertThat(decision.allowedTool()).isNull();
        assertThat(decision.aclaracion()).isNull();
        assertThat(decision.draft()).isNull();
    }

    @Test
    void scheduleRequestIsNotClaimedByAnyDeterministicIntent() {
        assertThat(IntencionAclarable.inicioLocal(HORARIO, null, RELOJ)).isNull();
        assertThat(HistorialPdfIntent.classify(HORARIO, RELOJ)).isNull();
        assertThat(ContribucionIntent.classify(HORARIO, null, RELOJ)).isNull();
    }

    @Test
    void compensationStillRecognizesItsOwnVocabulary() {
        var state = CompensationIntent.classify("Activa mi método de compensación gasolina", null);
        assertThat(state.allowedTool()).isEqualTo("cambiarEstadoMetodoCompensacion");

        var deleted = CompensationIntent.classify("Desactiva mi Yape", null);
        assertThat(deleted.recoveryArgs()).isEqualTo(Map.of("descripcionActual", "Yape", "activo", false));

        var registered = CompensationIntent.classify("Registra un método FAVOR", null);
        assertThat(registered.message()).contains("descripción");
    }
}
