package com.uniride.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BusquedaRespuesta {

    private Long viajeId;
    private LocalDate fecha;
    private LocalTime hora;
    private String dia;
    private Long rutaId;
    private String origen;
    private String destino;
    private String puntosReferencia;
    private String zonasSinParada;
    private String notaContribucion;
    private int capacidadMaxima;
    private Long conductorId;
    private String conductorNombre;
}
