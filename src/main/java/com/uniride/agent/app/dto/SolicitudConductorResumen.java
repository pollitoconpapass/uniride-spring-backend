package com.uniride.agent.app.dto;

import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.TipoCompensacion;
import java.time.LocalDate;
import java.time.LocalTime;

public record SolicitudConductorResumen(Long solicitudId, Long viajeId, String pasajero, EstadoSolicitud estado,
        TipoCompensacion preferencia, String origen, String destino, LocalDate fecha, LocalTime hora) { }
