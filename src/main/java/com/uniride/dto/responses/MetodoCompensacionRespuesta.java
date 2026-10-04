package com.uniride.dto.responses;

import com.uniride.enums.TipoCompensacion;

public record MetodoCompensacionRespuesta(
        Long id,
        TipoCompensacion tipo,
        String descripcion,
        boolean activo) {
}
