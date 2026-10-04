package com.uniride.dto.requests;

import com.uniride.enums.TipoCompensacion;
import jakarta.validation.constraints.NotNull;

public record SolicitudRequest(
        String mensaje,
        @NotNull(message = "La preferencia de compensación es obligatoria")
        TipoCompensacion preferenciaCompensacion,
        Long metodoCompensacionId) {
}
