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
public class SugerenciaContribucionRespuesta {

    private Long viajeId;
    private String origen;
    private String destino;
    private String dia;
    private LocalDate fecha;
    private LocalTime hora;
    private int pasajeros;
    private boolean esHoraPico;
    private double costoTotalEstimado;
    private double aporteEstimado;
    private double litrosGasolinaAprox;
    private String sugerenciaFavor;
    private String nota;
}
