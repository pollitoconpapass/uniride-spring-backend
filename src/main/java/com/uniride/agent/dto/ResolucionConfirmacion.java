package com.uniride.agent.dto;

/** Resultado de una decisión escrita. La confirmación proviene del registro persistido. */
public record ResolucionConfirmacion(Estado estado, ConfirmacionResumen confirmacion) {
    public enum Estado {
        EJECUTADA, CANCELADA, EXPIRADA, YA_EJECUTADA, YA_CANCELADA,
        RECHAZADA, SIN_PENDIENTE, ACLARACION_REQUERIDA, RESULTADO_INCIERTO
    }
}
