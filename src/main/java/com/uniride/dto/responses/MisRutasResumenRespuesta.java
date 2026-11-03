package com.uniride.dto.responses;

import org.springframework.data.domain.Page;

/** Contrato adicional de US09: conservar Page original y mostrar mensaje orientativo. */
public record MisRutasResumenRespuesta(
        Page<RutaRespuesta> rutas,
        String mensaje) {
}
