package com.uniride.dto.requests;

import jakarta.validation.constraints.NotBlank;

public record VerificarCodigoRequest(
        @NotBlank(message = "El correo es obligatorio")
        String correo,
        @NotBlank(message = "El código de verificación es obligatorio")
        String codigo) {
}
