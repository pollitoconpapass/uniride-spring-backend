package com.uniride.dto.requests;

import com.uniride.enums.TipoCompensacion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record MetodoCompensacionRequest(
        @NotNull(message = "El tipo de compensación es obligatorio")
        TipoCompensacion tipo,
        @NotBlank(message = "La descripción es obligatoria")
        String descripcion,
        Boolean activo) {
}
