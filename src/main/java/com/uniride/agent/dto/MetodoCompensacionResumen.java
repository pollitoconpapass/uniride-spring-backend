package com.uniride.agent.dto;

import com.uniride.enums.TipoCompensacion;

public record MetodoCompensacionResumen(
        Long id,
        TipoCompensacion tipo,
        String descripcion,
        boolean activo) {
}
