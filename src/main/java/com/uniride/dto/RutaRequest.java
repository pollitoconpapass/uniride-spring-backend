package com.uniride.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RutaRequest {

    @NotBlank(message = "El origen (punto de salida) es obligatorio")
    private String origen;

    @NotNull(message = "La capacidad máxima es obligatoria")
    @Min(value = 1, message = "La capacidad mínima es de 1 pasajero")
    private Integer capacidadMaxima;

    @NotBlank(message = "Los puntos de referencia son obligatorios")
    private String puntosReferencia;

    private String zonasSinParada;

    private String notaContribucion;
}
