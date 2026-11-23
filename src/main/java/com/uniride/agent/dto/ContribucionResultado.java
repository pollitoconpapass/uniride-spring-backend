package com.uniride.agent.dto;

import com.uniride.dto.responses.SugerenciaContribucionRespuesta;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Read-only US29 result with the original service DTO when the trip and count are known. */
public record ContribucionResultado(Estado estado, String mensaje, SugerenciaContribucionRespuesta sugerencia,
                                    List<Opcion> alternativas, boolean hayMasAlternativas) {
    public enum Estado { SUGERIDA, AMBIGUA, NO_ENCONTRADO, FALTAN_FILTROS, FALTAN_PASAJEROS }

    public record Opcion(String origen, String destino, String dia, LocalDate fecha, LocalTime hora, String conductor) { }
}
