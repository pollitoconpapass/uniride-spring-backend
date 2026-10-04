package com.uniride.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;

public record CursoRequest(
        @NotBlank(message = "El nombre del curso es obligatorio")
        String nombre,
        @NotBlank(message = "El día del curso es obligatorio")
        String dia,
        @NotNull(message = "La hora de inicio es obligatoria")
        LocalTime horaInicio,
        @NotNull(message = "La hora de fin es obligatoria")
        LocalTime horaFin) {
}
