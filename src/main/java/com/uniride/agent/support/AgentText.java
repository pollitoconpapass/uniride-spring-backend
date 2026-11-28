package com.uniride.agent.support;

import com.uniride.agent.conversation.AgentConversationStore;
import com.uniride.agent.dto.CursoResumen;
import com.uniride.agent.dto.PerfilAgenteRespuesta;
import com.uniride.agent.dto.ResultadoAgente;
import com.uniride.dto.responses.AnalisisSemanalRespuesta;
import com.uniride.dto.responses.DiaCantidadRespuesta;
import com.uniride.dto.responses.EstadisticasRespuesta;
import com.uniride.dto.responses.PerfilRespuesta;
import com.uniride.dto.responses.RankingRutasRespuesta;
import com.uniride.dto.responses.RutaFrecuenteRespuesta;
import com.uniride.dto.responses.UsuarioRespuesta;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Text normalization and summaries grounded in tool receipts. */
public final class AgentText {
    private static final Pattern DIACRITICOS = Pattern.compile("\\p{M}+");
    private static final String CAPACIDADES = "Puedo consultar datos de UniRide o preparar una operación para tu confirmación.";
    private static final Pattern PREGUNTA = Pattern.compile(
            "(?:^|(?<=\\s))(?<pregunta>¿[^?¿\\r\\n<>]{1,300}\\?|(?:qu[eé]|cu[aá]l(?:es)?|"
                    + "cu[aá]ndo|d[oó]nde)\\s[^?¿\\r\\n<>]{1,300}\\?)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private AgentText() { }

    public static String sinAcentos(String texto) {
        return DIACRITICOS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD)).replaceAll("")
                .toLowerCase(Locale.ROOT);
    }

    public static String normalizar(String texto) {
        return texto == null ? "" : sinAcentos(texto).strip().replaceAll("^[¿¡]+|[.!?]+$", "").strip();
    }

    public static String withoutResults(String user, String text) {
        var tema = normalizar(user);
        if (tema.matches("^(?:hola|buenos dias|buenas tardes|buenas noches)[, ]*(?:uniride)?$"))
            return "Hola. ¿En qué te ayudo con UniRide?";
        if (tema.matches("^(?:gracias|muchas gracias|ok|listo)$")) return "De nada.";
        if (tema.matches(".*\\b(?:que puedes hacer|en que puedes ayudarme|que sabes hacer|como me puedes ayudar)\\b.*"))
            return CAPACIDADES;
        if (tema.matches("^(?:por favor,? )?(?:no|nunca|jamas)\\b.*\\bmetodos?\\b.*"))
            return "No se prepararon cambios en tus métodos de compensación.";
        if (tema.matches("^(?:como|que pasa si|explicame|puedo)\\b.*"))
            return "¿Sobre qué parte de esa operación de UniRide necesitas información?";
        if (tema.matches(".*\\b(?:clima|pronostico|receta|chiste)\\b.*"))
            return "Puedo ayudarte con movilidad universitaria y tu cuenta de UniRide.";
        var pregunta = PREGUNTA.matcher(text);
        if (pregunta.find() && !AgentConversationStore.containsSecrets(text)
                && !normalizar(text).matches("(?s).*(?:https?://|www\\.|/api/|\\buuid\\b|\\botp\\b|\\bcontrasen\\w*|"
                        + "\\bjwt\\b|\\btoken\\b|\\bapi key\\b|\\bclave\\b|\\bya (?:he |se )?(?:cre|regis|elimi|"
                        + "cancel|confirm|ejecut|program|prepar|complet)\\w*).*")) return pregunta.group("pregunta");
        return "No pude identificar la consulta o acción de UniRide. ¿Qué dato quieres consultar o qué cambio quieres preparar?";
    }

    public static String personal(ResultadoAgente result, ObjectMapper mapper) {
        return switch (result.tipo()) {
            case "consultarMiPerfil" -> perfil(mapper.treeToValue(result.datos(), PerfilAgenteRespuesta.class));
            case "consultarMiUsuario" -> usuario(mapper.treeToValue(result.datos(), UsuarioRespuesta.class));
            case "consultarMisHorarios" -> horarios(result.datos(), mapper);
            default -> null;
        };
    }

    private static String perfil(PerfilAgenteRespuesta datos) {
        if (!datos.completo() || datos.perfil() == null) {
            return datos.mensaje();
        }
        PerfilRespuesta p = datos.perfil();
        List<String> partes = new ArrayList<>();
        partes.add("Tu perfil personal: %s %s, carrera %s en %s, distrito %s"
                .formatted(p.nombre(), p.apellidos(), p.carrera(), p.universidad(), p.distrito()));
        partes.add("Disponibilidad: %s (%s), clases del %s al %s"
                .formatted(p.diasDisponibles(), p.horariosDisponibles(),
                        p.fechaInicioClases(), p.fechaFinClases()));
        if (p.metodoCompensacionFavorito() != null) {
            partes.add("Método favorito: " + p.metodoCompensacionFavorito());
        }
        if (noBlanco(p.gustos())) partes.add("Gustos: " + p.gustos());
        if (noBlanco(p.hobbies())) partes.add("Hobbies: " + p.hobbies());
        if (noBlanco(p.datosCuriosos())) partes.add("Datos curiosos: " + p.datosCuriosos());
        return String.join(". ", partes) + ".";
    }

    private static String usuario(UsuarioRespuesta u) {
        return "Tu cuenta: %s %s (%s), teléfono %s, rol activo %s, cuenta %s."
                .formatted(u.nombre(), u.apellidos(), u.correo(), u.telefono(),
                        u.rol() == null ? "sin rol asignado" : u.rol().name(),
                        u.cuentaVerificada() ? "verificada" : "pendiente de verificación");
    }

    private static String horarios(JsonNode datos, ObjectMapper mapper) {
        JsonNode itemsNode = datos.path("items");
        List<CursoResumen> cursos = new ArrayList<>();
        itemsNode.forEach(item -> cursos.add(mapper.treeToValue(item, CursoResumen.class)));
        if (cursos.isEmpty()) {
            return "No encontré cursos registrados en tu horario académico para esa consulta.";
        }
        String lista = cursos.stream()
                .map(c -> "%s (%s %s - %s)".formatted(c.nombre(), c.dia(), c.horaInicio(), c.horaFin()))
                .collect(Collectors.joining("; "));
        boolean hayMas = datos.path("hayMas").asBoolean(false);
        return (cursos.size() == 1 ? "Curso registrado: " : "Cursos registrados (" + cursos.size() + "): ")
                + lista + "." + (hayMas ? " Hay más cursos; puedes pedir la siguiente página." : "");
    }

    private static boolean noBlanco(String value) {
        return value != null && !value.isBlank();
    }

    public static String report(ResultadoAgente result, ObjectMapper mapper) {
        return switch (result.tipo()) {
            case "consultarMisEstadisticas" -> resumen(mapper.treeToValue(result.datos(), EstadisticasRespuesta.class));
            case "consultarMiFrecuenciaViajes" -> frecuencia(mapper.treeToValue(result.datos(), AnalisisSemanalRespuesta.class));
            case "consultarRankingMisRutas" -> ranking(mapper.treeToValue(result.datos(), RankingRutasRespuesta.class));
            default -> null;
        };
    }

    private static String resumen(EstadisticasRespuesta datos) {
        long total = datos.viajesComoConductor() + datos.viajesComoPasajero();
        return "Has completado %s (%d como conductor y %d como pasajero). También registras %s y %s."
                .formatted(cantidad(total, "viaje", "viajes"), datos.viajesComoConductor(), datos.viajesComoPasajero(),
                        cantidad(datos.viajesCancelados(), "viaje cancelado", "viajes cancelados"),
                        cantidad(datos.penalidadesAcumuladas(), "penalidad acumulada", "penalidades acumuladas"));
    }

    private static String frecuencia(AnalisisSemanalRespuesta datos) {
        String cancelaciones = " Cancelaciones de viajes atribuibles a ti (%d) y a terceros (%d)."
                .formatted(datos.canceladosPorMi(), datos.canceladosPorTerceros());
        if (!datos.suficientesDatos()) return datos.mensaje() + cancelaciones;

        String distribucion = datos.viajesPorDia().stream()
                .map(dia -> "%s (%d)".formatted(dia.dia(), dia.cantidad()))
                .collect(Collectors.joining(", "));
        long maximo = datos.viajesPorDia().stream().mapToLong(DiaCantidadRespuesta::cantidad).max().orElseThrow();
        String diasMasFrecuentes = datos.viajesPorDia().stream()
                .filter(dia -> dia.cantidad() == maximo)
                .map(DiaCantidadRespuesta::dia)
                .collect(Collectors.joining(", "));
        return "Tu frecuencia histórica de viajes completados por día es %s. Mayor frecuencia en %s (%s por día)."
                .formatted(distribucion, diasMasFrecuentes, cantidad(maximo, "viaje", "viajes")) + cancelaciones;
    }

    private static String ranking(RankingRutasRespuesta datos) {
        if (!datos.suficientesDatos()) return datos.mensaje();
        if (datos.ranking().isEmpty()) return "Tu ranking de rutas como conductor está vacío.";

        RutaFrecuenteRespuesta masUsada = datos.ranking().getFirst();
        boolean empateEnUso = datos.ranking().size() > 1 && datos.ranking().get(1).cantidad() == masUsada.cantidad();
        String uso = "%s de %s a %s, con %s y %s."
                .formatted(empateEnUso ? "Una de tus rutas más utilizadas como conductor va"
                                : "Tu ruta más utilizada como conductor va",
                        masUsada.origen(), masUsada.destino(),
                        cantidad(masUsada.cantidad(), "viaje completado", "viajes completados"),
                        cantidad(masUsada.penalidades(), "penalidad", "penalidades"));

        RutaFrecuenteRespuesta masPenalizada = datos.ranking().stream()
                .max(Comparator.comparingLong(RutaFrecuenteRespuesta::penalidades)).orElseThrow();
        if (masPenalizada.penalidades() == 0) return uso + " El ranking registra cero penalidades.";

        boolean empateEnPenalidades = datos.ranking().stream()
                .filter(ruta -> ruta.penalidades() == masPenalizada.penalidades()).limit(2).count() > 1;
        if (masUsada.equals(masPenalizada)) {
            return uso + (empateEnPenalidades ? " Comparte el máximo de penalidades con otras rutas."
                    : " Es también la ruta con más penalidades.");
        }
        return uso + " %s de %s a %s, con %s."
                .formatted(empateEnPenalidades ? "Una de las rutas con más penalidades va"
                                : "La ruta con más penalidades va",
                        masPenalizada.origen(), masPenalizada.destino(),
                        cantidad(masPenalizada.penalidades(), "penalidad", "penalidades"));
    }

    private static String cantidad(long valor, String singular, String plural) {
        return valor + " " + (valor == 1 ? singular : plural);
    }
}
