package com.uniride.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RutaFrecuenteRespuesta {

    private String origen;
    private String destino;
    private long cantidad;
    private long penalidades;
}
