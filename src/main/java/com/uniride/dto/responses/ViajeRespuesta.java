package com.uniride.dto.responses;

import com.uniride.enums.EstadoViaje;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record ViajeRespuesta(
        Long id,
        Long rutaId,
        LocalDate fecha,
        LocalTime hora,
        String dia,
        boolean confirmado,
        LocalDateTime fechaConfirmacion,
        EstadoViaje estado,
        int pasajeros,
        String mensaje) {
}
