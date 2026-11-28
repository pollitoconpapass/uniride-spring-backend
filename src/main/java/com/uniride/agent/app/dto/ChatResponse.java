package com.uniride.agent.app.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record ChatResponse(
        String message,
        List<ViajeResumen> viajes,
        List<RutaResumen> rutas,
        List<MetodoCompensacionResumen> metodosCompensacion,
        OperacionResumen operacion,
        List<ResultadoAgente> resultados,
        AdvertenciaAgente advertencia,
        java.util.UUID conversationId,
        @JsonInclude(JsonInclude.Include.NON_NULL) ResolucionConfirmacion resolucionConfirmacion,
        @JsonInclude(JsonInclude.Include.NON_NULL) AclaracionAgente aclaracion) {

    public ChatResponse(String message, List<ViajeResumen> viajes, List<RutaResumen> rutas,
                        List<MetodoCompensacionResumen> metodosCompensacion, OperacionResumen operacion,
                        List<ResultadoAgente> resultados, AdvertenciaAgente advertencia, java.util.UUID conversationId,
                        ResolucionConfirmacion resolucionConfirmacion) {
        this(message, viajes, rutas, metodosCompensacion, operacion, resultados, advertencia, conversationId,
                resolucionConfirmacion, null);
    }

    public ChatResponse(String message, List<ViajeResumen> viajes, List<RutaResumen> rutas,
                        List<MetodoCompensacionResumen> metodosCompensacion, OperacionResumen operacion,
                        List<ResultadoAgente> resultados, AdvertenciaAgente advertencia, java.util.UUID conversationId) {
        this(message, viajes, rutas, metodosCompensacion, operacion, resultados, advertencia, conversationId, null);
    }

    public ChatResponse(String message, List<ViajeResumen> viajes, List<RutaResumen> rutas,
                        List<MetodoCompensacionResumen> metodosCompensacion, OperacionResumen operacion,
                        List<ResultadoAgente> resultados, AdvertenciaAgente advertencia) {
        this(message, viajes, rutas, metodosCompensacion, operacion, resultados, advertencia, null);
    }

    public ChatResponse withConversationId(java.util.UUID id) {
        return new ChatResponse(message, viajes, rutas, metodosCompensacion, operacion, resultados, advertencia, id,
                resolucionConfirmacion, aclaracion);
    }

    public ChatResponse conAclaracion(AclaracionAgente dato) {
        return new ChatResponse(message, viajes, rutas, metodosCompensacion, operacion, resultados, advertencia,
                conversationId, resolucionConfirmacion, dato);
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
