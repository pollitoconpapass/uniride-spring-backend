package com.uniride.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CampoAclaracion(String id, String etiqueta, TipoCampoAclaracion tipo, boolean obligatorio,
        Object inicial, List<OpcionAclaracion> opciones, Integer minimo, Integer maximo,
        Integer longitudMaxima, String ayuda) {
    private static final Set<String> IDS = Set.of("origen", "destino", "origenActual", "destinoActual", "nuevoOrigen",
            "referencias", "capacidad", "zonasSinParada", "notaContribucion", "dia", "fecha", "hora", "nuevaFecha",
            "nuevaHora", "compensacion", "descripcion", "tipoActual", "descripcionActual", "nuevoTipo",
            "nuevaDescripcion", "activo", "pasajeros", "conductor", "desde", "hasta", "tipo", "periodo", "anio");

    public CampoAclaracion {
        Objects.requireNonNull(tipo);
        if (id == null || !IDS.contains(id) || !AclaracionAgente.textoSeguro(etiqueta, 120)
                || (ayuda != null && !AclaracionAgente.textoSeguro(ayuda, 240))) {
            throw new IllegalArgumentException("Campo de aclaración inválido");
        }
        opciones = opciones == null ? null : List.copyOf(opciones);
        if (tipo == TipoCampoAclaracion.SELECT ? opciones == null || opciones.isEmpty() || opciones.size() > 10
                || opciones.stream().map(OpcionAclaracion::valor).distinct().count() != opciones.size() : opciones != null) {
            throw new IllegalArgumentException("Opciones de aclaración inválidas");
        }
        if ((minimo != null || maximo != null) && (tipo != TipoCampoAclaracion.NUMBER
                || minimo != null && maximo != null && minimo > maximo)
                || longitudMaxima != null && (longitudMaxima < 1 || longitudMaxima > 1000
                || tipo != TipoCampoAclaracion.TEXT && tipo != TipoCampoAclaracion.TEXTAREA)
                || inicial != null && !(inicial instanceof Integer && tipo == TipoCampoAclaracion.NUMBER
                || inicial instanceof String texto && AclaracionAgente.textoSeguro(texto, 255)
                && (opciones == null || opciones.stream().anyMatch(opcion -> opcion.valor().equals(texto))))) {
            throw new IllegalArgumentException("Restricciones de aclaración inválidas");
        }
    }
}
