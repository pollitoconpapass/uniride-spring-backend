package com.uniride.dto.requests;

import jakarta.validation.constraints.NotBlank;

public record VerificarCodigoRequest(
        @NotBlank(message = "El correo es obligatorio")
        @jakarta.validation.constraints.Size(max = 254)
        String correo,
        @NotBlank(message = "El código de verificación es obligatorio")
        @jakarta.validation.constraints.Pattern(regexp = "[0-9]{6}", message = "El código debe tener seis dígitos")
        String codigo) {
    public VerificarCodigoRequest {
        correo = com.uniride.util.ValidacionUtil.normalizarCorreo(correo);
    }
}
