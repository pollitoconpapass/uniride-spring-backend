package com.uniride.agent.dto;

public record OpcionAclaracion(String valor, String etiqueta) {
    public OpcionAclaracion {
        if (!AclaracionAgente.textoSeguro(valor, 255) || !AclaracionAgente.textoSeguro(etiqueta, 255)) {
            throw new IllegalArgumentException("Opción de aclaración inválida");
        }
    }
}
