package com.uniride.dto.requests;

import com.uniride.enums.TipoCompensacion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record PerfilRequest(
        @NotBlank(message = "El nombre es obligatorio")
        String nombre,
        @NotBlank(message = "Los apellidos son obligatorios")
        String apellidos,
        @NotBlank(message = "La carrera es obligatoria")
        String carrera,
        @NotBlank(message = "El distrito es obligatorio")
        String distrito,
        @NotBlank(message = "La universidad es obligatoria")
        String universidad,
        @NotBlank(message = "Los días disponibles son obligatorios")
        String diasDisponibles,
        @NotBlank(message = "Los horarios disponibles son obligatorios")
        String horariosDisponibles,
        @NotNull(message = "La fecha de inicio de clases es obligatoria")
        LocalDate fechaInicioClases,
        @NotNull(message = "La fecha de fin de clases es obligatoria")
        LocalDate fechaFinClases,
        TipoCompensacion metodoCompensacionFavorito) {
}
