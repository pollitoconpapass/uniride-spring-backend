package com.uniride.dto.responses;

import com.uniride.enums.EstadoViaje;
import java.util.List;

/** Datos historicos de solo lectura: estado original y penalidades propias asociadas. */
public record SolicitudHistorialRespuesta(
        SolicitudRespuesta solicitud,
        EstadoViaje estadoViaje,
        boolean soloLectura,
        List<PenalidadHistorialRespuesta> penalidades) {
}
