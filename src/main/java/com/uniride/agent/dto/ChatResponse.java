package com.uniride.agent.dto;

import java.util.List;

public record ChatResponse(
        String message,
        List<ViajeResumen> viajes,
        List<RutaResumen> rutas,
        List<MetodoCompensacionResumen> metodosCompensacion,
        OperacionResumen operacion) {
}
