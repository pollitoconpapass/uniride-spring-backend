package com.uniride.agent.dto;

import java.time.LocalTime;

public record CursoResumen(
        Long id,
        String nombre,
        String dia,
        LocalTime horaInicio,
        LocalTime horaFin) {
}
