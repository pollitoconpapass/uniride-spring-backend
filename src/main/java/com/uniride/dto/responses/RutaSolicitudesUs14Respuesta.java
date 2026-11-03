package com.uniride.dto.responses;

import com.uniride.enums.EstadoRuta;
import java.util.List;

/** Agrupacion de solicitudes de todos los viajes de una ruta del conductor. */
public record RutaSolicitudesUs14Respuesta(
        Long rutaId,
        String origen,
        String destino,
        EstadoRuta estado,
        List<SolicitudRecibidaUs14Respuesta> solicitudes,
        String mensaje) {
}
