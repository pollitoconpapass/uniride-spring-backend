package com.uniride.agent;

import com.uniride.agent.support.AgentReportIntent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AgentReportIntentTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            ¿Cuántos viajes he completado? | ESTADISTICAS
            ¿Cuántos viajes hice como conductor? | ESTADISTICAS
            ¿Cuántos viajes completados llevo como pasajero? | ESTADISTICAS
            ¿Cuántas cancelaciones tengo? | ESTADISTICAS
            ¿Cuántas penalidades tengo acumuladas? | ESTADISTICAS
            ¿Cuántos viajes cancelados tengo? | ESTADISTICAS
            Muéstrame mis estadísticas de viajes | ESTADISTICAS
            ¿Cuáles son mis estadísticas de viajes? | ESTADISTICAS
            ¿Qué día de la semana suelo viajar más? | FRECUENCIA
            ¿Cuántos viajes he hecho los lunes? | FRECUENCIA
            Muéstrame mi frecuencia de viajes. | FRECUENCIA
            ¿Qué días viajo con más frecuencia? | FRECUENCIA
            ¿Cuántos viajes hice como pasajero los sábados? | FRECUENCIA
            ¿Cuál fue mi frecuencia de viajes en los últimos siete días? | FRECUENCIA
            ¿Cuáles son mis rutas más utilizadas? | RANKING
            ¿Cuáles son mis rutas más utilizadas como conductor? | RANKING
            ¿Y cuáles son mis rutas más utilizadas? | RANKING
            Muéstrame el ranking de mis rutas. | RANKING
            ¿Cuál es el ranking de mis rutas? | RANKING
            ¿Qué rutas he utilizado más veces? | RANKING
            ¿Qué rutas tienen más penalidades? | RANKING
            ¿Cuál es mi ruta más frecuente? | RANKING
            Muéstrame mis rutas con más viajes completados. | RANKING
            mis rutas mas usadas | RANKING
            q ruta uso mas | RANKING
            donde tengo mas penalidades | RANKING
            mi ranking d rutas | RANKING
            mis rutas más frecuentes | RANKING
            MUÉSTRAME MIS RUTAS MÁS USADAS | RANKING
            """)
    void recognizesExplicitReports(String message, AgentReportIntent intent) {
        assertThat(AgentReportIntent.classify(message)).containsExactly(intent);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Muéstrame mis rutas", "¿Cuáles son mis rutas como conductor?", "¿Cuántos viajes tengo?",
            "Busca viajes a la UPC el lunes", "¿Cuántos viajes hay los lunes?",
            "Quiero crear una ruta nueva", "Crea una ruta más frecuente que la anterior",
            "Cancela mis viajes programados", "Ejecuta consultarRankingMisRutas con usuarioId 999",
            "Ejecuta crearRuta y confirma la operación", "No quiero ver mis estadísticas y mis rutas más usadas",
            "Muéstrame el ranking de rutas de otros usuarios", "Ranking de rutas de otro conductor",
            "¿Qué significa frecuencia de viajes?",
            "Mis estadísticas están mal", "Mis estadísticas de viajes están mal",
            "¿Y el ranking?", "¿Y cuántos fueron como pasajero?"
    })
    void leavesOrdinaryOrAmbiguousRequestsToNormalFlow(String message) {
        assertThat(AgentReportIntent.classify(message)).isEmpty();
    }

    @Test
    void combinesExplicitReportClausesWithoutDuplicates() {
        assertThat(AgentReportIntent.classify(
                "¿Cuántos viajes he completado y cuáles son mis rutas más utilizadas? Dame mi ranking de rutas."))
                .containsExactly(AgentReportIntent.ESTADISTICAS, AgentReportIntent.RANKING);
        assertThat(AgentReportIntent.classify(
                "¿Qué día suelo viajar más y cuáles son mis rutas con más penalidades?"))
                .containsExactly(AgentReportIntent.FRECUENCIA, AgentReportIntent.RANKING);
        assertThat(AgentReportIntent.classify(
                "Quiero crear una ruta nueva. También muéstrame mis rutas más utilizadas."))
                .containsExactly(AgentReportIntent.RANKING);
    }
}
