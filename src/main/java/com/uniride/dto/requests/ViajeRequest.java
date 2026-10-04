package com.uniride.dto.requests;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

public record ViajeRequest(
        @NotNull(message = "La fecha es obligatoria")
        LocalDate fecha,
        @NotNull(message = "La hora es obligatoria")
        LocalTime hora) {
}
