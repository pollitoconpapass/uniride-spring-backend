package com.uniride.dto.requests;

import jakarta.validation.constraints.NotBlank;

public record ReenviarCodigoRequest(
        @NotBlank(message = "El correo es obligatorio")
        @jakarta.validation.constraints.Size(max = 254)
        String correo) {
    public ReenviarCodigoRequest {
        correo = com.uniride.util.ValidacionUtil.normalizarCorreo(correo);
    }
}
