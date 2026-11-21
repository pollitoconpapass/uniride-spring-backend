package com.uniride.agent.dto;

public record OperacionResumen(
        EstadoOperacion estado,
        Long id,
        String detalle) {
}
