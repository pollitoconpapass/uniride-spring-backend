package com.uniride.dto.responses;

import java.time.LocalDate;
import java.time.LocalTime;

public record BusquedaRespuesta(
        Long viajeId,
        LocalDate fecha,
        LocalTime hora,
        String dia,
        Long rutaId,
        String origen,
        String destino,
        String puntosReferencia,
        String zonasSinParada,
        String notaContribucion,
        int capacidadMaxima,
        Long conductorId,
        String conductorNombre) {
}
