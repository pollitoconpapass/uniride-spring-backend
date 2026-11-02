package com.uniride.dto.responses;

import org.springframework.data.domain.Page;

/** Respuesta ampliada para US10, sin modificar el contrato paginado de /buscar. */
public record BusquedaResumenRespuesta(
        Page<BusquedaRespuesta> viajes,
        String mensaje,
        String sugerencia) {
}
