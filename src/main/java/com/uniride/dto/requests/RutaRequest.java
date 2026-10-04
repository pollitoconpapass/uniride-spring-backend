package com.uniride.dto.requests;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RutaRequest(
        @NotBlank(message = "El origen (punto de salida) es obligatorio")
        String origen,
        @NotNull(message = "La capacidad máxima es obligatoria")
        @Min(value = 1, message = "La capacidad mínima es de 1 pasajero")
        Integer capacidadMaxima,
        @NotBlank(message = "Los puntos de referencia son obligatorios")
        String puntosReferencia,
        String zonasSinParada,
        String notaContribucion) {
}
