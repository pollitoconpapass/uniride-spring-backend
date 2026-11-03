package com.uniride.dto.responses;

import com.uniride.enums.EstadoSolicitud;

/** Detalle accesible solo al conductor propietario de la ruta del viaje. */
public record DetalleSolicitudRecibidaUs14Respuesta(
        Long solicitudId,
        Long rutaId,
        Long viajeId,
        EstadoSolicitud estado,
        String mensajeSolicitud,
        PerfilPasajeroUs14Respuesta pasajero,
        String mensaje) {
}
