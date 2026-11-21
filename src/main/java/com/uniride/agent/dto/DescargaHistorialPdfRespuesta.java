package com.uniride.agent.dto;

/** Parameters for a later authenticated download. Dates are ISO calendar dates when present. */
public record DescargaHistorialPdfRespuesta(
        String nombreArchivo,
        String urlDescarga,
        String desde,
        String hasta,
        String tipo,
        String mensaje) { }
