package com.uniride.agent.app.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.uniride.dto.responses.SugerenciaContribucionRespuesta;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Read-only US29 result with the original service DTO when the trip and count are known. */
public record ContribucionResultado(Estado estado, String mensaje, SugerenciaContribucionRespuesta sugerencia,
                                    List<Opcion> alternativas, boolean hayMasAlternativas,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) AclaracionAgente aclaracion) {
    public ContribucionResultado(Estado estado, String mensaje, SugerenciaContribucionRespuesta sugerencia,
                                 List<Opcion> alternativas, boolean hayMasAlternativas) {
        this(estado, mensaje, sugerencia, alternativas, hayMasAlternativas, null);
    }

    public enum Estado { SUGERIDA, AMBIGUA, NO_ENCONTRADO, FALTAN_FILTROS, FALTAN_PASAJEROS }

    public record Opcion(String origen, String destino, String dia, LocalDate fecha, LocalTime hora, String conductor) { }
}
