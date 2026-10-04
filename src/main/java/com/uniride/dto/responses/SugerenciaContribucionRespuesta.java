package com.uniride.dto.responses;

import java.time.LocalDate;
import java.time.LocalTime;

public record SugerenciaContribucionRespuesta(
        Long viajeId,
        String origen,
        String destino,
        String dia,
        LocalDate fecha,
        LocalTime hora,
        int pasajeros,
        boolean esHoraPico,
        double costoTotalEstimado,
        double aporteEstimado,
        double litrosGasolinaAprox,
        String sugerenciaFavor,
        String nota) {
}
