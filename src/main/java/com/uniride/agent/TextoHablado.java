package com.uniride.agent;

import com.uniride.agent.dto.ChatResponse;
import com.uniride.agent.dto.MetodoCompensacionResumen;
import com.uniride.agent.dto.RutaResumen;
import com.uniride.agent.dto.ViajeResumen;
import com.uniride.enums.EstadoRuta;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Builds the spoken text from verified data. The screen keeps the full detail. */
final class TextoHablado {

    private static final int MAX_ITEMS = 5;
    private static final int MAX_CHARS = 900;
    private static final Locale ES = Locale.forLanguageTag("es-PE");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("d 'de' MMMM", ES);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("H:mm", ES);
    private static final Pattern MARKDOWN = Pattern.compile("[*_#`]+");

    private TextoHablado() { }

    static String desde(ChatResponse respuesta) {
        List<String> partes = new ArrayList<>();
        String mensaje = limpiar(respuesta.message());
        if (!mensaje.isBlank()) partes.add(mensaje);
        if (!respuesta.rutas().isEmpty())
            partes.add(enumerar("ruta", "rutas", respuesta.rutas().stream().map(TextoHablado::ruta).toList()));
        if (!respuesta.viajes().isEmpty())
            partes.add(enumerar("viaje", "viajes", respuesta.viajes().stream().map(TextoHablado::viaje).toList()));
        if (!respuesta.metodosCompensacion().isEmpty())
            partes.add(enumerar("método de compensación", "métodos de compensación",
                    respuesta.metodosCompensacion().stream().map(TextoHablado::metodo).toList()));
        if (respuesta.resultados() != null
                && respuesta.resultados().stream().anyMatch(r -> r.datos().path("hayMas").asBoolean(false)))
            partes.add("Hay más resultados en pantalla.");
        return limitar(String.join(" ", partes));
    }

    private static String ruta(RutaResumen ruta) {
        String texto = "%s a %s".formatted(ruta.origen(), ruta.destino());
        return ruta.estado() == null || ruta.estado() == EstadoRuta.ACTIVA
                ? texto : texto + ", " + ruta.estado().name().toLowerCase(ES);
    }

    private static String viaje(ViajeResumen viaje) {
        StringBuilder texto = new StringBuilder("%s a %s".formatted(viaje.origen(), viaje.destino()));
        if (viaje.fecha() != null) texto.append(", el ").append(viaje.fecha().format(FECHA));
        if (viaje.hora() != null) texto.append(" a las ").append(viaje.hora().format(HORA));
        if (viaje.conductorNombre() != null && !viaje.conductorNombre().isBlank())
            texto.append(", con ").append(viaje.conductorNombre());
        if (viaje.estadoSolicitud() != null)
            texto.append(", solicitud ").append(viaje.estadoSolicitud().name().toLowerCase(ES));
        return texto.toString();
    }

    private static String metodo(MetodoCompensacionResumen metodo) {
        String descripcion = metodo.descripcion() == null || metodo.descripcion().isBlank()
                ? "" : ", " + metodo.descripcion();
        return metodo.tipo() + descripcion + (metodo.activo() ? ", activo" : ", inactivo");
    }

    private static String enumerar(String singular, String plural, List<String> items) {
        int visibles = Math.min(items.size(), MAX_ITEMS);
        String texto = (visibles == 1 ? "Tu " + singular + " es: " : "Tus " + plural + " son: ")
                + String.join("; ", items.subList(0, visibles)) + ".";
        if (items.size() > visibles) texto += " Hay " + (items.size() - visibles) + " más en pantalla.";
        return texto;
    }

    private static String limpiar(String texto) {
        if (texto == null) return "";
        return MARKDOWN.matcher(texto.replace('•', ' ')).replaceAll("")
                .replaceAll("\\s+", " ").strip();
    }

    private static String limitar(String texto) {
        if (texto.length() <= MAX_CHARS) return texto;
        int corte = texto.lastIndexOf('.', MAX_CHARS);
        return (corte > MAX_CHARS / 2 ? texto.substring(0, corte + 1) : texto.substring(0, MAX_CHARS)).strip();
    }
}
