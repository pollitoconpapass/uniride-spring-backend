package com.uniride.agent.app.dto;

import java.time.LocalTime;

public record CursoResumen(
        Long id,
        String nombre,
        String dia,
        LocalTime horaInicio,
        LocalTime horaFin) {
}
