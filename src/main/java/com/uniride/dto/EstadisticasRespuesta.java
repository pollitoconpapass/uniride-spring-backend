package com.uniride.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EstadisticasRespuesta {

    private long viajesComoConductor;
    private long viajesComoPasajero;
    private long viajesCancelados;
    private long penalidadesAcumuladas;
}
