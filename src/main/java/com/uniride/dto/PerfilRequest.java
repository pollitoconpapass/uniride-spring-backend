package com.uniride.dto;

import com.uniride.enums.TipoCompensacion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PerfilRequest {

    @NotBlank(message = "El nombre es obligatorio")
    private String nombre;

    @NotBlank(message = "Los apellidos son obligatorios")
    private String apellidos;

    @NotBlank(message = "La carrera es obligatoria")
    private String carrera;

    @NotBlank(message = "El distrito es obligatorio")
    private String distrito;

    @NotBlank(message = "La universidad es obligatoria")
    private String universidad;

    @NotBlank(message = "Los días disponibles son obligatorios")
    private String diasDisponibles;

    @NotBlank(message = "Los horarios disponibles son obligatorios")
    private String horariosDisponibles;

    @NotNull(message = "La fecha de inicio de clases es obligatoria")
    private LocalDate fechaInicioClases;

    @NotNull(message = "La fecha de fin de clases es obligatoria")
    private LocalDate fechaFinClases;

    private TipoCompensacion metodoCompensacionFavorito;
}
