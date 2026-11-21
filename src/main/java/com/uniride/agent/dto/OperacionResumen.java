package com.uniride.agent.dto;

public record OperacionResumen(
        EstadoOperacion estado,
        Long id,
        String detalle,
        ConfirmacionResumen confirmacion) {

    public OperacionResumen(EstadoOperacion estado, Long id, String detalle) {
        this(estado, id, detalle, null);
    }
}
