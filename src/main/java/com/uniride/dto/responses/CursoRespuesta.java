package com.uniride.dto.responses;

import java.time.LocalTime;

public record CursoRespuesta(
        Long id,
        String nombre,
        String dia,
        LocalTime horaInicio,
        LocalTime horaFin) {
}
