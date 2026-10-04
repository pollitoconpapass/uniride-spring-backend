package com.uniride.dto.responses;

import java.util.List;

public record RankingRutasRespuesta(
        boolean suficientesDatos,
        String mensaje,
        List<RutaFrecuenteRespuesta> ranking) {
}
