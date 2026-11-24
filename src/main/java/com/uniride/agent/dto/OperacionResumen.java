package com.uniride.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public record OperacionResumen(
        EstadoOperacion estado,
        Long id,
        String detalle,
        ConfirmacionResumen confirmacion,
        @JsonInclude(JsonInclude.Include.NON_NULL) AclaracionAgente aclaracion) {

    public OperacionResumen(EstadoOperacion estado, Long id, String detalle, ConfirmacionResumen confirmacion) {
        this(estado, id, detalle, confirmacion, null);
    }

    public OperacionResumen(EstadoOperacion estado, Long id, String detalle) {
        this(estado, id, detalle, null);
    }

    public OperacionResumen conAclaracion(AclaracionAgente dato) {
        return new OperacionResumen(estado, id, detalle, confirmacion, dato);
    }
}
