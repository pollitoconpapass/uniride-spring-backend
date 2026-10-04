package com.uniride.dto;

import com.uniride.enums.TipoCompensacion;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SolicitudRequest {

    private String mensaje;

    @NotNull(message = "La preferencia de compensación es obligatoria")
    private TipoCompensacion preferenciaCompensacion;

    private Long metodoCompensacionId;
}
