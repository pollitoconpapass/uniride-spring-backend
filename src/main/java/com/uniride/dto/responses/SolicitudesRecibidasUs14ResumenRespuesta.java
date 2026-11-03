package com.uniride.dto.responses;

import org.springframework.data.domain.Page;

/** Pagina de rutas con solicitudes agrupadas y mensajes orientativos. */
public record SolicitudesRecibidasUs14ResumenRespuesta(
        Page<RutaSolicitudesUs14Respuesta> rutas,
        String mensaje) {
}
