package com.uniride.agent.app.intent;

import static org.assertj.core.api.Assertions.assertThat;
import com.uniride.agent.app.dto.CampoAclaracion;
import com.uniride.agent.app.intent.HistorialPdfIntent.Filtros;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class HistorialPdfIntentTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void pideSoloElLimiteInvalidoYConservaLaFechaValida() {
        var solicitud = HistorialPdfIntent.classify("Exporta mis viajes entre 2026-02-30 y 2026-04-19", CLOCK);
        assertThat(solicitud.aclaracionEstructurada().campos()).extracting(com.uniride.agent.app.dto.CampoAclaracion::id)
                .containsExactly("desde");
        var corregida = HistorialPdfIntent.continuarCampos(Map.of("desde", "2026-03-01"), solicitud, CLOCK);
        assertThat(corregida.filtros()).isEqualTo(new Filtros(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 19), "todos"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "N/A", textBlock = """
            Genera un PDF con todo mi historial de viajes | N/A | N/A | todos
            Quiero descargar mi historial | N/A | N/A | todos
            Quiero descargar todo mi historial de viajes en PDF | N/A | N/A | todos
            Genera un PDF con mis viajes como conductor | N/A | N/A | conductor
            Descarga mi historial como pasajero | N/A | N/A | pasajero
            EXPORTA MIS VIAJES DE OCTUBRE DE 2026 | 2026-10-01 | 2026-10-31 | todos
            Exporta mis viajes de febrero de 2024 | 2024-02-01 | 2024-02-29 | todos
            Exporta mis viajes de octubre de este año como conductora | 2026-10-01 | 2026-10-31 | conductor
            Exporta mis viajes de octubre del año pasado | 2025-10-01 | 2025-10-31 | todos
            Dame mi historial de viajes del mes pasado | 2026-10-01 | 2026-10-31 | todos
            Descarga mi historial de este mes | 2026-11-01 | 2026-11-15 | todos
            Quiero un reporte en PDF de los últimos 30 días | 2026-10-17 | 2026-11-15 | todos
            Exporta mis viajes entre el 1 y el 15 de noviembre de 2026 | 2026-11-01 | 2026-11-15 | todos
            Exporta mis viajes del 1 al 15 de noviembre de 2026 | 2026-11-01 | 2026-11-15 | todos
            Exporta mi historial desde el 1 de octubre de 2026 | 2026-10-01 | N/A | todos
            Exporta mi historial hasta el 31 de octubre de 2026 | N/A | 2026-10-31 | todos
            Descarga mi historial desde 2026-10-01 hasta 2026-11-15 como pasajera | 2026-10-01 | 2026-11-15 | pasajero
            Descarga mi historial, desde el 1 de octubre de 2026 | 2026-10-01 | N/A | todos
            ¿Cuántos viajes he completado? Y descarga mi historial de octubre de 2026 | 2026-10-01 | 2026-10-31 | todos
            Descarga mi historial de octubre de 2026 y dime mis estadísticas | 2026-10-01 | 2026-10-31 | todos
            Quiero un PDF con mi historial de octubre de 2026 | 2026-10-01 | 2026-10-31 | todos
            Quiero mi historial en PDF | N/A | N/A | todos
            Descarga en PDF mi historial | N/A | N/A | todos
            """)
    void resolvesRequestedFilters(String message, String desde, String hasta, String tipo) {
        var solicitud = HistorialPdfIntent.classify(message, CLOCK);

        assertThat(solicitud).isNotNull();
        assertThat(solicitud.aclaracion()).isNull();
        assertThat(solicitud.filtros()).isEqualTo(new Filtros(fecha(desde), fecha(hasta), tipo));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            2027-01-15T00:30:00Z | 2026-12-01 | 2026-12-31
            2024-03-02T00:30:00Z | 2024-02-01 | 2024-02-29
            2026-11-01T00:30:00Z | 2026-09-01 | 2026-09-30
            """)
    void resolvesPreviousMonthAtTheLimaCalendarBoundary(String instant, String desde, String hasta) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);

        var solicitud = HistorialPdfIntent.classify("Descarga mi historial del mes pasado", clock);

        assertThat(solicitud.filtros()).isEqualTo(new Filtros(fecha(desde), fecha(hasta), "todos"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Genera mi historial del último mes | mes calendario pasado
            Descarga mis viajes de octubre | De qué año
            Descarga mi historial desde 2026-02-29 | fecha inválida
            Descarga mi historial hasta el 31 de abril de 2026 | fecha inválida
            Descarga mi historial desde el 30 de noviembre de 2026 hasta el 1 de noviembre de 2026 | posterior
            Descarga mi historial como administrador | ambos roles
            Exporta mis viajes del primer trimestre | Qué período
            Descarga mi historial desde el | Qué período
            Descarga mi historial. Desde octubre. | Qué período
            Descarga mi historial de octubre de 2026 o de noviembre de 2026 | Qué período
            Descarga mi historial desde 2026-10-01 y exporta mis viajes de noviembre de 2026 | una sola solicitud
            """)
    void asksForClarificationWithoutProducingFilters(String message, String aclaracion) {
        var solicitud = HistorialPdfIntent.classify(message, CLOCK);

        assertThat(solicitud).isNotNull();
        assertThat(solicitud.filtros()).isNull();
        assertThat(solicitud.aclaracion()).contains(aclaracion);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "¿Cómo descargo mi historial en PDF?", "¿Puedo descargar mi historial?", "Muéstrame mi historial",
            "Quiero ver mi historial", "Dame mis viajes", "Dame mi historial de viajes", "Mis estadísticas",
            "Mis rutas más utilizadas", "Quiero un reporte de mis viajes", "Elimina mi historial de viajes",
            "Descarga el historial de otro conductor", "Descarga mi historial con usuarioId 123",
            "No quiero descargar mi historial", "octubre de 2026"
    })
    void leavesOtherIntentsToTheRegularAgentFlow(String message) {
        assertThat(HistorialPdfIntent.classify(message, CLOCK)).isNull();
    }

    @Test
    void deduplicatesAnExplicitDownloadRepeatedInTheSameMessage() {
        var solicitud = HistorialPdfIntent.classify("Descarga mi historial. Descarga mi historial en PDF.", CLOCK);

        assertThat(solicitud.filtros()).isEqualTo(new Filtros(null, null, "todos"));
    }

    private static LocalDate fecha(String valor) {
        return valor == null ? null : LocalDate.parse(valor);
    }
}
