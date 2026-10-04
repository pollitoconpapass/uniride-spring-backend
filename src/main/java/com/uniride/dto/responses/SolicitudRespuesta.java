package com.uniride.dto.responses;

import com.uniride.enums.EstadoAcuerdo;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.TipoCompensacion;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record SolicitudRespuesta(
        Long id,
        Long viajeId,
        LocalDate fecha,
        LocalTime hora,
        String dia,
        Long rutaId,
        String origen,
        String destino,
        String mensaje,
        TipoCompensacion preferenciaCompensacion,
        EstadoSolicitud estado,
        String motivoRechazo,
        LocalDateTime fechaCreacion,
        String advertencia,
        Long pasajeroId,
        String pasajeroNombre,
        String pasajeroCorreo,
        String pasajeroCarrera,
        String pasajeroDistrito,
        String acuerdoTerminos,
        EstadoAcuerdo acuerdoEstado,
        Long metodoCompensacionId,
        TipoCompensacion metodoCompensacionTipo,
        String metodoCompensacionDescripcion) {
}
