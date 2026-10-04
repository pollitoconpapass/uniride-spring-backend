package com.uniride.dto.responses;

public record RutaFrecuenteRespuesta(
        String origen,
        String destino,
        long cantidad,
        long penalidades) {
}
