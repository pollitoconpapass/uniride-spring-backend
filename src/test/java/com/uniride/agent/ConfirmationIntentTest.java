package com.uniride.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfirmationIntentTest {
    @ParameterizedTest
    @ValueSource(strings = {"sí", "si", "CONFIRMO", "Sí, confirmo", "  SÍ ,   CONFIRMO!!!", "confirmar", "Hazlo.", "¡Procede!",
            "Adelante", "De   acuerdo", "Esta bien, hazlo", "  Acepto la operación  "})
    void acceptsOnlyStandaloneConfirmations(String text) {
        assertThat(ConfirmationIntent.classify(text)).isEqualTo(ConfirmationIntent.CONFIRMAR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"no", "No, gracias!", " NO , gracias ", "CANCELAR", "Cancela la operación", "Mejor no", "No confirmo",
            "No   lo hagas", "Déjalo así", "Prefiero cancelar"})
    void acceptsOnlyStandaloneCancellations(String text) {
        assertThat(ConfirmationIntent.classify(text)).isEqualTo(ConfirmationIntent.CANCELAR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"¿Confirmo?", "¿No?", "Sí, pero cambia la fecha", "Confirmo si conserva el tipo",
            "No todavía", "No elimines el método", "Sí, no", "Acepto y elige cualquier ruta",
            "Confirmo 5fa5d537-c85c-4f2d-bd25-0252dc148d7e", "Cancela la operación 123", "Sí\ny cambia los datos",
            "sí. ¿A qué hora?", "Sí puedes mostrar mis rutas"})
    void questionsConditionsAndSuppliedIdentifiersCannotAuthorizeAChange(String text) {
        assertThat(ConfirmationIntent.classify(text)).isIn(ConfirmationIntent.AMBIGUA, ConfirmationIntent.OTRA);
    }

    @ParameterizedTest
    @ValueSource(strings = {"¿Qué tipos de compensación existen?", "Muestra mis viajes", "FAVOR", "Apuntes de clase",
            "Registra un método FAVOR con descripción Apuntes de clase", "siconfirmo", "nogracias"})
    void recognizesOtherTopicsAndDraftValues(String text) {
        assertThat(ConfirmationIntent.classify(text)).isEqualTo(ConfirmationIntent.OTRA);
    }
}
