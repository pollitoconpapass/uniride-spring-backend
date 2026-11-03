package com.uniride.dto.responses;

import com.uniride.enums.EstadoSolicitud;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Datos necesarios para evaluar una solicitud sin exponer datos de contacto. */
public record SolicitudRecibidaUs14Respuesta(
        Long solicitudId,
        Long viajeId,
        LocalDate fechaViaje,
        LocalTime horaViaje,
        EstadoSolicitud estado,
        String pasajeroNombre,
        String pasajeroCarrera,
        String pasajeroDistrito,
        String mensajeSolicitud,
        LocalDateTime fechaSolicitud) {
}
