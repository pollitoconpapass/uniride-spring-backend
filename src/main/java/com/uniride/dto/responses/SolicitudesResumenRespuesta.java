package com.uniride.dto.responses;

import org.springframework.data.domain.Page;

/** US13: mensaje opcional sin modificar GET /solicitudes existente. */
public record SolicitudesResumenRespuesta(
        Page<SolicitudRespuesta> solicitudes,
        String mensaje) {
}
