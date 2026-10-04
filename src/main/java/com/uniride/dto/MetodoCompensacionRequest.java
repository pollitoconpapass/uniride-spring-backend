package com.uniride.dto;

import com.uniride.enums.TipoCompensacion;
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
public class MetodoCompensacionRequest {

    @NotNull(message = "El tipo de compensación es obligatorio")
    private TipoCompensacion tipo;

    @NotBlank(message = "La descripción es obligatoria")
    private String descripcion;

    private Boolean activo;
}
