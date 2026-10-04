package com.uniride.dto.responses;

import com.uniride.enums.EstadoRuta;
import java.time.LocalDateTime;

public record RutaRespuesta(
        Long id,
        String origen,
        String destino,
        String puntosReferencia,
        String zonasSinParada,
        String notaContribucion,
        int capacidadMaxima,
        EstadoRuta estado,
        LocalDateTime fechaCreacion,
        Long conductorId,
        String conductorNombre) {
}
