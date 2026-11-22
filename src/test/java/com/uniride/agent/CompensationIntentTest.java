package com.uniride.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.dto.OperacionResumen;
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
            "Activa mi método GASOLINA\nElimina mi método favor", "Cambia mi método Yape al tipo BITCOIN",
            "Cambia la descripción de mi método Yape a Yape personal y elimina FAVOR", "Elimina mi método \"123\"",
            "Cambia la descripción de mi método Yape a Personal elimina FAVOR", "Elimina mi método Yape modifica FAVOR",
            "Elimina mi método \"Yape\" o \"Gasolina\""})
    void blocksPreparationsWhenTheRequestIsInformativeNegatedAmbiguousOrIncomplete(String text) {
        var decision = CompensationIntent.classify(text, null);
        assertThat(decision.blockPreparations()).isTrue();
        assertThat(decision.allowedTool()).isNull();
        assertThat(decision.recoveryArgs()).isNull();
        assertThat(decision.draft()).isNull();
    }

    @Test
    void incompleteEditAsksForTheNewDescription() {
        var question = CompensationIntent.classify("Cambia la descripción de mi método Yape", null);
        assertThat(question.message())
                .contains("nueva descripción").doesNotContain("Prefieres compensar");
        assertThat(question.blockPreparations()).isTrue();
        assertThat(question.recoveryArgs()).isNull();
        assertThat(CompensationIntent.classify("Yape personal", question.draft()).recoveryArgs())
                .isEqualTo(Map.of("descripcionActual", "Yape", "nuevaDescripcion", "Yape personal"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Cambia la descripción de mi método Yape a Yape personal",
            "Cambia la descripción de Yape a Yape personal", "Cambia mi Yape a Yape personal"})
    void preparesOnlyTheExplicitEditValues(String text) {
        var decision = CompensationIntent.classify(text, null);
        assertThat(decision.allowedTool()).isEqualTo("actualizarMetodoCompensacion");
        assertThat(decision.recoveryArgs()).isEqualTo(Map.of("descripcionActual", "Yape", "nuevaDescripcion", "Yape personal"));
    }

    @Test
    void preservesExplicitFieldAndQuotedDescription() {
        assertThat(CompensationIntent.classify("Cambia mi método Yape al tipo DINERO", null).recoveryArgs())
                .isEqualTo(Map.of("descripcionActual", "Yape", "nuevoTipo", TipoCompensacion.DINERO));
        assertThat(CompensationIntent.classify("Cambia la descripción de mi método «Pago a clase» a «FAVOR»", null).recoveryArgs())
                .isEqualTo(Map.of("descripcionActual", "Pago a clase", "nuevaDescripcion", "FAVOR"));
        assertThat(CompensationIntent.classify("Cambia mi método Yape a FAVOR", null).blockPreparations()).isTrue();
        assertThat(CompensationIntent.classify("Cambia la descripción de mi ruta Yape a Yape personal", null).allowedTool()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Elimina mi método FAVOR", "Elimina método FAVOR", "Elimina mi método de tipo FAVOR"})
    void recoversOnlyTheExplicitDeletionSelector(String text) {
        var decision = CompensationIntent.classify(text, null);
        assertThat(decision.allowedTool()).isEqualTo("eliminarMetodoCompensacion");
        assertThat(decision.recoveryArgs()).isEqualTo(Map.of("tipoActual", TipoCompensacion.FAVOR));
    }

    @Test
    void doesNotInferTypeFromUnqualifiedDescription() {
        assertThat(CompensationIntent.classify("Quiero quitar Favor con apuntes de mis métodos", null).recoveryArgs())
                .isEqualTo(Map.of("descripcionActual", "Favor con apuntes"));
        assertThat(CompensationIntent.classify("Elimina mi método de tipo FAVOR llamado Favor con apuntes", null).recoveryArgs())
                .isEqualTo(Map.of("tipoActual", TipoCompensacion.FAVOR, "descripcionActual", "Favor con apuntes"));
    }

    @Test
    void keepsOnlyTheRequestedDeletionWhenItsSelectorIsMissing() {
        var question = CompensationIntent.classify("Elimina mi método", null);
        assertThat(question.message()).contains("tipo", "descripción");
        assertThat(question.recoveryArgs()).isNull();
        var next = CompensationIntent.classify("FAVOR", question.draft());
        assertThat(next.allowedTool()).isEqualTo("eliminarMetodoCompensacion");
        assertThat(next.recoveryArgs()).isEqualTo(Map.of("tipoActual", TipoCompensacion.FAVOR));
        assertThat(CompensationIntent.classify("Elimina", null).draft()).isNull();
        assertThat(CompensationIntent.classify("Busca viajes a la UPC", question.draft()).draft()).isNull();
    }

    @Test
    void onlyNarrowsSelectorsAfterAuthoritativeAmbiguity() {
        var request = CompensationIntent.classify("Cambia el tipo de mi método FAVOR a DINERO", null);
        var ambiguity = new OperacionResumen(EstadoOperacion.AMBIGUO, null, "Varios métodos.");
        var draft = CompensationIntent.afterResponse(request, ambiguity);
        var clarified = CompensationIntent.classify("Apuntes de clase", draft);
        assertThat(clarified.recoveryArgs()).isEqualTo(Map.of("tipoActual", TipoCompensacion.FAVOR,
                "descripcionActual", "Apuntes de clase", "nuevoTipo", TipoCompensacion.DINERO));
        assertThat(CompensationIntent.classify("GASOLINA", draft).recoveryArgs()).isNull();
        assertThat(CompensationIntent.afterResponse(clarified,
                new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Preparada."))).isNull();

        var deletion = CompensationIntent.classify("Elimina mi método Yape", null);
        var deletionDraft = CompensationIntent.afterResponse(deletion, ambiguity);
        assertThat(CompensationIntent.classify("Yape personal", deletionDraft).recoveryArgs())
                .isEqualTo(Map.of("descripcionActual", "Yape personal"));
        assertThat(CompensationIntent.classify("Efectivo", deletionDraft).recoveryArgs()).isNull();
    }

    @Test
    void classifiesStaticInformationSeparatelyFromPreparingAnOperation() {
        var types = CompensationIntent.classify("¿Qué tipos de compensación existen?", null);
        assertThat(types.informational()).isTrue();
        assertThat(types.message()).contains("DINERO", "GASOLINA", "FAVOR");
        assertThat(types.recoveryArgs()).isNull();
        var procedure = CompensationIntent.classify("¿Cómo puedo registrar, actualizar o eliminar un método?", null);
        assertThat(procedure.informational()).isTrue();
        assertThat(procedure.message()).contains("registrar", "actualizar", "eliminar", "confirma");
        assertThat(procedure.allowedTool()).isNull();
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
