package com.uniride.dto.requests;

import jakarta.validation.constraints.NotBlank;

public record ReenviarCodigoRequest(
        @NotBlank(message = "El correo es obligatorio")
        String correo) {
}
