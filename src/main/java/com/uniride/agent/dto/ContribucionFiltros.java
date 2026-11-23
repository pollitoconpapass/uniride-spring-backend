package com.uniride.agent.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/** Selectors derived in Java from the user's conversation. Carries no account or trip identifiers. */
public record ContribucionFiltros(String origen, String destino, String dia, LocalDate fecha, LocalTime hora,
                                 Integer pasajeros, String conductor, boolean viajePropio) {
    public boolean tieneReferenciaViaje() {
        return viajePropio || fecha != null || hora != null || tieneTexto(origen) || tieneTexto(destino)
                || tieneTexto(dia) || tieneTexto(conductor);
    }

    private static boolean tieneTexto(String valor) {
        return valor != null && !valor.isBlank();
    }
}
