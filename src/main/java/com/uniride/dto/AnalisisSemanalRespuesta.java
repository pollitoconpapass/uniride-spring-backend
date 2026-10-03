package com.uniride.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalisisSemanalRespuesta {

    private boolean suficientesDatos;
    private String mensaje;
    private List<DiaCantidadRespuesta> viajesPorDia;
    private long canceladosPorMi;
    private long canceladosPorTerceros;
}
