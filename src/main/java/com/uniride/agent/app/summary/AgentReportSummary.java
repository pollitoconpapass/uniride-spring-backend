package com.uniride.agent.app.summary;

import com.uniride.agent.app.dto.ResultadoAgente;
import com.uniride.dto.responses.AnalisisSemanalRespuesta;
import com.uniride.dto.responses.DiaCantidadRespuesta;
import com.uniride.dto.responses.EstadisticasRespuesta;
import com.uniride.dto.responses.RankingRutasRespuesta;
import com.uniride.dto.responses.RutaFrecuenteRespuesta;
import java.util.Comparator;
import java.util.stream.Collectors;
import tools.jackson.databind.ObjectMapper;

/** Presents reports already captured from the corresponding Java tool. */
public final class AgentReportSummary {
    private AgentReportSummary() { }

    public static String describe(ResultadoAgente result, ObjectMapper mapper) {
        return switch (result.tipo()) {
            case "consultarMisEstadisticas" -> resumen(mapper.treeToValue(result.datos(), EstadisticasRespuesta.class));
            case "consultarMiFrecuenciaViajes" -> frecuencia(mapper.treeToValue(result.datos(), AnalisisSemanalRespuesta.class));
            case "consultarRankingMisRutas" -> ranking(mapper.treeToValue(result.datos(), RankingRutasRespuesta.class));
            default -> null;
        };
    }

    private static String resumen(EstadisticasRespuesta datos) {
        long total = datos.viajesComoConductor() + datos.viajesComoPasajero();
        return "Has completado %s (%d como conductor y %d como pasajero). También registras %s y %s."
                .formatted(cantidad(total, "viaje", "viajes"), datos.viajesComoConductor(), datos.viajesComoPasajero(),
                        cantidad(datos.viajesCancelados(), "viaje cancelado", "viajes cancelados"),
                        cantidad(datos.penalidadesAcumuladas(), "penalidad acumulada", "penalidades acumuladas"));
    }

    private static String frecuencia(AnalisisSemanalRespuesta datos) {
        String cancelaciones = " Cancelaciones de viajes atribuibles a ti (%d) y a terceros (%d)."
                .formatted(datos.canceladosPorMi(), datos.canceladosPorTerceros());
        if (!datos.suficientesDatos()) return datos.mensaje() + cancelaciones;

        String distribucion = datos.viajesPorDia().stream()
                .map(dia -> "%s (%d)".formatted(dia.dia(), dia.cantidad()))
                .collect(Collectors.joining(", "));
        long maximo = datos.viajesPorDia().stream().mapToLong(DiaCantidadRespuesta::cantidad).max().orElseThrow();
        String diasMasFrecuentes = datos.viajesPorDia().stream()
                .filter(dia -> dia.cantidad() == maximo)
                .map(DiaCantidadRespuesta::dia)
                .collect(Collectors.joining(", "));
        return "Tu frecuencia histórica de viajes completados por día es %s. Mayor frecuencia en %s (%s por día)."
                .formatted(distribucion, diasMasFrecuentes, cantidad(maximo, "viaje", "viajes")) + cancelaciones;
    }

    private static String ranking(RankingRutasRespuesta datos) {
        if (!datos.suficientesDatos()) return datos.mensaje();
        if (datos.ranking().isEmpty()) return "Tu ranking de rutas como conductor está vacío.";

        RutaFrecuenteRespuesta masUsada = datos.ranking().getFirst();
        boolean empateEnUso = datos.ranking().size() > 1 && datos.ranking().get(1).cantidad() == masUsada.cantidad();
        String uso = "%s de %s a %s, con %s y %s."
                .formatted(empateEnUso ? "Una de tus rutas más utilizadas como conductor va"
                                : "Tu ruta más utilizada como conductor va",
                        masUsada.origen(), masUsada.destino(),
                        cantidad(masUsada.cantidad(), "viaje completado", "viajes completados"),
                        cantidad(masUsada.penalidades(), "penalidad", "penalidades"));

        RutaFrecuenteRespuesta masPenalizada = datos.ranking().stream()
                .max(Comparator.comparingLong(RutaFrecuenteRespuesta::penalidades)).orElseThrow();
        if (masPenalizada.penalidades() == 0) return uso + " El ranking registra cero penalidades.";

        boolean empateEnPenalidades = datos.ranking().stream()
                .filter(ruta -> ruta.penalidades() == masPenalizada.penalidades()).limit(2).count() > 1;
        if (masUsada.equals(masPenalizada)) {
            return uso + (empateEnPenalidades ? " Comparte el máximo de penalidades con otras rutas."
                    : " Es también la ruta con más penalidades.");
        }
        return uso + " %s de %s a %s, con %s."
                .formatted(empateEnPenalidades ? "Una de las rutas con más penalidades va"
                                : "La ruta con más penalidades va",
                        masPenalizada.origen(), masPenalizada.destino(),
                        cantidad(masPenalizada.penalidades(), "penalidad", "penalidades"));
    }

    private static String cantidad(long valor, String singular, String plural) {
        return valor + " " + (valor == 1 ? singular : plural);
    }
}
