package com.uniride.agent.dto;

import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.EstadoViaje;
import java.time.LocalDate;
import java.time.LocalTime;

public record ViajeResumen(
        Long viajeId,
        Long rutaId,
        String origen,
        String destino,
        String dia,
        LocalDate fecha,
        LocalTime hora,
        String puntosReferencia,
        String conductorNombre,
        Integer capacidadMaxima,
        EstadoViaje estado,
        EstadoSolicitud estadoSolicitud) {
}
