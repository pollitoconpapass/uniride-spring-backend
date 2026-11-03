package com.uniride.dto.responses;

import com.uniride.enums.TipoPenalidad;
import java.time.LocalDateTime;

/** Solo penalidades del pasajero autenticado para el viaje de esta solicitud. */
public record PenalidadHistorialRespuesta(
        Long id,
        TipoPenalidad tipo,
        String motivo,
        LocalDateTime fecha) {
}
