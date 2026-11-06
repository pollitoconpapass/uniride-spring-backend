package com.uniride.dto.requests;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "El correo es obligatorio")
        @jakarta.validation.constraints.Size(max = 254)
        String correo,
        @NotBlank(message = "La contraseña es obligatoria")
        String contrasena) {
    public LoginRequest {
        correo = com.uniride.util.ValidacionUtil.normalizarCorreo(correo);
    }
}
