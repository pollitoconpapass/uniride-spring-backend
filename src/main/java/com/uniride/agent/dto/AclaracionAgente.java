package com.uniride.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AclaracionAgente(int version, UUID id, String operacion, String contexto, List<CampoAclaracion> campos,
        List<String> alMenosUno, Boolean opcionesRapidas, String respuestaBreve) {
    private static final Map<String, List<String>> CAMPOS = Map.ofEntries(
            Map.entry("crearRuta", List.of("origen", "referencias", "capacidad", "zonasSinParada", "notaContribucion")),
            Map.entry("editarRuta", List.of("origenActual", "destinoActual", "nuevoOrigen", "referencias", "capacidad",
                    "zonasSinParada", "notaContribucion")),
            Map.entry("eliminarRuta", List.of("origenActual", "destinoActual")),
            Map.entry("crearViaje", List.of("ruta", "origen", "destino", "fecha", "hora")),
            Map.entry("reprogramarViaje", List.of("origen", "destino", "dia", "fecha", "hora", "nuevaFecha", "nuevaHora")),
            Map.entry("solicitarUnirse", List.of("viaje", "origen", "destino", "dia", "fecha", "hora", "compensacion")),
            Map.entry("cancelarSolicitud", List.of("origen", "destino", "dia", "fecha", "hora")),
            Map.entry("consultarDetalleSolicitudRecibida", List.of("solicitud")),
            Map.entry("aceptarSolicitud", List.of("solicitud")),
            Map.entry("rechazarSolicitud", List.of("solicitud")),
            Map.entry("rechazarSolicitudesPendientes", List.of("viaje")),
            Map.entry("confirmarViaje", List.of("viaje")),
            Map.entry("iniciarViaje", List.of("viaje")),
            Map.entry("completarViaje", List.of("viaje")),
            Map.entry("cancelarViaje", List.of("viaje")),
            Map.entry("consultarEstadoNotificaciones", List.of("viaje")),
            Map.entry("registrarMetodoCompensacion", List.of("compensacion", "descripcion")),
            Map.entry("actualizarMetodoCompensacion", List.of("tipoActual", "descripcionActual", "nuevoTipo", "nuevaDescripcion")),
            Map.entry("cambiarEstadoMetodoCompensacion", List.of("tipoActual", "descripcionActual", "activo")),
            Map.entry("eliminarMetodoCompensacion", List.of("tipoActual", "descripcionActual")),
            Map.entry("sugerirContribucionViaje", List.of("origen", "destino", "dia", "fecha", "hora", "conductor", "pasajeros")),
            Map.entry("prepararDescargaHistorialPdf", List.of("desde", "hasta", "tipo", "periodo", "anio")),
            Map.entry("editarPerfil", List.of("campoPerfil", "nombre", "apellidos", "carrera", "distrito", "universidad",
                    "diasDisponibles", "horariosDisponibles", "fechaInicioClases", "fechaFinClases", "metodoCompensacionFavorito")),
            Map.entry("editarInfoAdicional", List.of("gustos", "hobbies", "datosCuriosos")),
            Map.entry("cambiarRol", List.of("rol")),
            Map.entry("registrarCurso", List.of("nombreCurso", "dia", "horaInicio", "horaFin", "accionCurso")),
            Map.entry("eliminarCurso", List.of("curso", "nombreCurso", "dia", "horaInicio")));
    private static final Pattern NO_PERMITIDO = Pattern.compile("(?iu)[<>\\p{Cntrl}]|\\b(?:https?|ftp|file|javascript|data|vbscript):|www\\.|/api/|"
            + "bearer\\s|eyJ[\\w.-]{12,}|(?:gsk_|sk-)[\\w-]{12,}|"
            + "\\b(?:jwt|contrase[nñ]a|password|api[_ -]?key|clave de api)\\b");

    public AclaracionAgente {
        campos = List.copyOf(campos);
        var alternativos = alMenosUno == null ? null : List.copyOf(alMenosUno);
        var ids = new HashSet<>(campos.stream().map(CampoAclaracion::id).toList());
        if (version != 1 || operacion == null || !CAMPOS.getOrDefault(operacion, List.of()).containsAll(ids) || !textoSeguro(contexto, 120)
                || campos.isEmpty() || campos.size() > 8 || ids.size() != campos.size()
                || alternativos != null && (alternativos.size() < 2 || !ids.containsAll(alternativos)
                || new HashSet<>(alternativos).size() != alternativos.size()
                || campos.stream().anyMatch(c -> c.obligatorio() && alternativos.contains(c.id())))) {
            throw new IllegalArgumentException("Aclaración inválida");
        }
        alMenosUno = alternativos;
        if (respuestaBreve != null && !"campo".equals(respuestaBreve)
                && !("valor".equals(respuestaBreve) && campos.size() == 1)
                || Boolean.TRUE.equals(opcionesRapidas) && (campos.size() != 1
                || campos.getFirst().tipo() != TipoCampoAclaracion.SELECT)) {
            throw new IllegalArgumentException("Formato de respuesta inválido");
        }
    }

    public AclaracionAgente conId(UUID nuevoId) {
        return new AclaracionAgente(version, nuevoId, operacion, contexto, campos, alMenosUno, opcionesRapidas, respuestaBreve);
    }

    public AclaracionAgente conRespuestaBreve(String formato) {
        return new AclaracionAgente(version, id, operacion, contexto, campos, alMenosUno, opcionesRapidas, formato);
    }

    public AclaracionAgente conOpcionesRapidas() {
        return new AclaracionAgente(version, id, operacion, contexto, campos, alMenosUno, true, respuestaBreve);
    }

    public static boolean textoSeguro(String valor, int limite) {
        return valor != null && !valor.isBlank() && valor.length() <= limite && !NO_PERMITIDO.matcher(valor).find();
    }
}
