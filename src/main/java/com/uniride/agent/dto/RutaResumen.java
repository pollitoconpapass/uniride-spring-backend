package com.uniride.agent.dto;

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
