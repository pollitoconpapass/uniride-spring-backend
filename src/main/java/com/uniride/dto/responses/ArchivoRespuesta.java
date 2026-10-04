package com.uniride.dto.responses;

public record ArchivoRespuesta(
        String mensaje,
        int cursosImportados,
        int cursosOmitidos,
        String nombreArchivo) {
}
