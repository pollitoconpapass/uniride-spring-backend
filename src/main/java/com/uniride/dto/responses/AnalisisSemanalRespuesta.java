package com.uniride.dto.responses;

import java.util.List;

public record AnalisisSemanalRespuesta(
        boolean suficientesDatos,
        String mensaje,
        List<DiaCantidadRespuesta> viajesPorDia,
        long canceladosPorMi,
        long canceladosPorTerceros) {
}
