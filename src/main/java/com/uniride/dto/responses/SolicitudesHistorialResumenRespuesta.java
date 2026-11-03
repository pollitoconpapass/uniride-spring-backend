package com.uniride.dto.responses;

import org.springframework.data.domain.Page;

/** Historial US13 paginado y mensaje explicativo cuando no hay registros. */
public record SolicitudesHistorialResumenRespuesta(
        Page<SolicitudHistorialRespuesta> solicitudes,
        String mensaje) {
}
