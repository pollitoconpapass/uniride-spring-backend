package com.uniride.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.enums.TipoCompensacion;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CompensationIntentTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Activa mi método de compensación gasolina | GASOLINA | true
            Activa mi método GASOLINA | GASOLINA | true
            Desactiva GASOLINA | GASOLINA | false
            ¿Puedes desactivar mi método de DINERO? | DINERO | false
            Activa nuevamente mi método de gasolina | GASOLINA | true
            """)
    void recoversOnlyTheExplicitStateAndType(String text, TipoCompensacion type, boolean active) {
        var decision = CompensationIntent.classify(text, null);
        assertThat(decision.allowedTool()).isEqualTo("cambiarEstadoMetodoCompensacion");
        assertThat(decision.recoveryArgs()).isEqualTo(Map.of("tipoActual", type, "activo", active));
        assertThat(decision.blockPreparations()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Desactiva mi método Yape", "Desactiva mi Yape", "Desactiva mi método Yape, por favor"})
    void keepsTheSpellingOfTheSelectedDescription(String text) {
        assertThat(CompensationIntent.classify(text, null).recoveryArgs())
                .isEqualTo(Map.of("descripcionActual", "Yape", "activo", false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Registra un método FAVOR", "Registra un método de compensación FAVOR",
            "Prefiero FAVOR, agrégalo", "Prefiero compensar con Favor, agrégalo"})
    void asksOnlyForTheMissingDescriptionAndUsesItInTheNextTurn(String text) {
        var missing = CompensationIntent.classify(text, null);
        assertThat(missing.message()).isEqualTo("¿Qué descripción quieres asignar a tu nuevo método FAVOR?");
        assertThat(missing.blockPreparations()).isTrue();
        assertThat(missing.recoveryArgs()).isNull();
        assertThat(missing.draft()).isEqualTo(new CompensationIntent.Registro(TipoCompensacion.FAVOR, null));

        var complete = CompensationIntent.classify("Apuntes de clase", missing.draft());
        assertThat(complete.allowedTool()).isEqualTo("registrarMetodoCompensacion");
        assertThat(complete.recoveryArgs()).isEqualTo(Map.of("tipo", TipoCompensacion.FAVOR, "descripcion", "Apuntes de clase"));
        assertThat(complete.draft()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Registra un método FAVOR con descripción Apuntes de clase",
            "Registra FAVOR con Apuntes de clase", "Registra un método FAVOR, descripción Apuntes de clase"})
    void recognizesACompleteRegistrationInOneTurn(String text) {
        assertThat(CompensationIntent.classify(text, null).recoveryArgs())
                .isEqualTo(Map.of("tipo", TipoCompensacion.FAVOR, "descripcion", "Apuntes de clase"));
    }

    @Test
    void preservesALabelWhenOnlyTheTypeWasMissing() {
        var first = CompensationIntent.classify("Registra un método llamado Apuntes de clase", null);
        assertThat(first.message()).contains("Qué tipo");
        assertThat(first.draft()).isEqualTo(new CompensationIntent.Registro(null, "Apuntes de clase"));
        assertThat(CompensationIntent.classify("FAVOR", first.draft()).recoveryArgs())
                .isEqualTo(Map.of("tipo", TipoCompensacion.FAVOR, "descripcion", "Apuntes de clase"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"¿Cómo activo un método?", "No quiero desactivar mi método", "¿Qué pasa si elimino Yape?",
            "Explícame cómo se editan los métodos", "¿Puedo eliminar un método?", "No elimines mi método de gasolina",
            "Activa mi método GASOLINA y desactiva Yape", "Activa mi método GASOLINA o DINERO", "Activa mi método 123",
            "Activa mi método usuarioId 123", "Elimina mi método Yape y otro", "Cambia mi método Yape y otro al tipo FAVOR",
            "Activa mi método GASOLINA\nElimina mi método favor",
            "Cambia la descripción de mi método Yape"})
    void blocksPreparationsWhenTheRequestIsInformativeNegatedAmbiguousOrIncomplete(String text) {
        var decision = CompensationIntent.classify(text, null);
        assertThat(decision.blockPreparations()).isTrue();
        assertThat(decision.allowedTool()).isNull();
        assertThat(decision.recoveryArgs()).isNull();
        assertThat(decision.draft()).isNull();
    }

    @Test
    void incompleteEditAsksForTheNewDescription() {
        assertThat(CompensationIntent.classify("Cambia la descripción de mi método Yape", null).message())
                .contains("nueva descripción").doesNotContain("Prefieres compensar");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Cambia la descripción de mi método Yape a Yape personal | actualizarMetodoCompensacion
            Cambia mi Yape a Yape personal | actualizarMetodoCompensacion
            Elimina mi método de gasolina | eliminarMetodoCompensacion
            Elimina mi Yape | eliminarMetodoCompensacion
            Quiero quitar Favor con apuntes de mis métodos | eliminarMetodoCompensacion
            """)
    void explicitEditAndDeletionCanUseTheProviderToolWithoutAutomaticRecovery(String text, String tool) {
        var decision = CompensationIntent.classify(text, null);
        assertThat(decision.allowedTool()).isEqualTo(tool);
        assertThat(decision.recoveryArgs()).isNull();
        assertThat(decision.message()).contains("método");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Busca viajes a la UPC", "Muéstrame mis métodos", "¿Cuántos viajes he hecho?",
            "Prefiero compensar con GASOLINA", "Quiero unirme a un viaje", "No quiero registrarlo", "Activa mi cuenta",
            "Bearer sample-token-fixture", "Hola", "Gracias"})
    void unrelatedTurnsClearTheDraftAndCannotUseItAsADescription(String text) {
        var draft = new CompensationIntent.Registro(TipoCompensacion.FAVOR, null);
        var decision = CompensationIntent.classify(text, draft);
        assertThat(decision.draft()).isNull();
        assertThat(decision.allowedTool()).isNull();
        assertThat(decision.recoveryArgs()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Sí", "Confirmo", "Está bien, hazlo", "Procede", "Acepto la operación", "¡SÍ!"})
    void recognizesBareAffirmationsWithoutGrantingPermissionToPrepare(String text) {
        assertThat(CompensationIntent.isAffirmation(text)).isTrue();
        assertThat(CompensationIntent.classify(text, null).blockPreparations()).isTrue();
    }

    @Test
    void distinguishesAnActualTripRequestFromMethodManagement() {
        assertThat(CompensationIntent.isTripRequest("Quiero unirme a un viaje")).isTrue();
        assertThat(CompensationIntent.isTripRequest("Activa mi método de compensación gasolina")).isFalse();
        assertThat(CompensationIntent.isAffirmation("Sí, pero cambia el tipo")).isFalse();
    }
}
