package com.uniride.dto.responses;

public record EstadisticasRespuesta(
        long viajesComoConductor,
        long viajesComoPasajero,
        long viajesCancelados,
        long penalidadesAcumuladas) {
}
