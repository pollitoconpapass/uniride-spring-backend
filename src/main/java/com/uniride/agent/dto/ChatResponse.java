package com.uniride.agent.dto;

import java.util.List;

public record ChatResponse(
        String message,
        List<ViajeResumen> viajes,
        List<RutaResumen> rutas,
        List<MetodoCompensacionResumen> metodosCompensacion,
        OperacionResumen operacion,
        List<ResultadoAgente> resultados,
        AdvertenciaAgente advertencia,
        java.util.UUID conversationId) {

    public ChatResponse(String message, List<ViajeResumen> viajes, List<RutaResumen> rutas,
                        List<MetodoCompensacionResumen> metodosCompensacion, OperacionResumen operacion,
                        List<ResultadoAgente> resultados, AdvertenciaAgente advertencia) {
        this(message, viajes, rutas, metodosCompensacion, operacion, resultados, advertencia, null);
    }

    public ChatResponse withConversationId(java.util.UUID id) {
        return new ChatResponse(message, viajes, rutas, metodosCompensacion, operacion, resultados, advertencia, id);
    }

    public ChatResponse(String message, List<ViajeResumen> viajes, List<RutaResumen> rutas,
                        List<MetodoCompensacionResumen> metodosCompensacion, OperacionResumen operacion,
                        List<ResultadoAgente> resultados) {
        this(message, viajes, rutas, metodosCompensacion, operacion, resultados, null);
    }

    public ChatResponse(String message, List<ViajeResumen> viajes, List<RutaResumen> rutas,
                        List<MetodoCompensacionResumen> metodosCompensacion, OperacionResumen operacion) {
        this(message, viajes, rutas, metodosCompensacion, operacion, List.of());
    }
}
