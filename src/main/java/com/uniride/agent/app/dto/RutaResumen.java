package com.uniride.agent.app.dto;

import com.uniride.enums.EstadoRuta;

public record RutaResumen(
        Long rutaId,
        String origen,
        String destino,
        String puntosReferencia,
        String zonasSinParada,
        Integer capacidadMaxima,
        EstadoRuta estado) {
}
