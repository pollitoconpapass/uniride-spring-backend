package com.uniride.agent.app.dto;

import com.uniride.enums.EstadoSolicitud;
import java.time.LocalDate;
import java.time.LocalTime;

/** Candidate for the passenger's own cancellation confirmation. */
public record SolicitudResumen(
        Long id,
        Long viajeId,
        String origen,
        String destino,
        String dia,
        LocalDate fecha,
        LocalTime hora,
        EstadoSolicitud estado) {
}
