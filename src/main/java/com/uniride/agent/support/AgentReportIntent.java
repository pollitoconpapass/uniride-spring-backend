package com.uniride.agent.support;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Recognizes explicit report requests in the current message only. */
public enum AgentReportIntent {
    ESTADISTICAS("consultarMisEstadisticas"),
    FRECUENCIA("consultarMiFrecuenciaViajes"),
    RANKING("consultarRankingMisRutas");

    private static final Pattern SPACES = Pattern.compile("[\\p{Z}\\t]+");
    private static final Pattern SENTENCES = Pattern.compile("[.!?¿,;¡\\r\\n]+");
    private static final Pattern CONJUNCTION = Pattern.compile(" +y +");
    private static final Pattern OTHER_OWNER = Pattern.compile(
            "\\bde (?:otr[oa]s?|tod[oa]s? l[oa]s) (?:usuari[oa]s?|conductor(?:es)?|pasajer[oa]s?|personas?)\\b");

    private static final String VIAJES_REALIZADOS = "viajes (?:he (?:completado|hecho|realizado)|"
            + "hice|complete|realice|llevo completados|(?:completados|realizados) (?:llevo|tengo))";
    private static final String DIAS = "(?:lunes|martes|miercoles|jueves|viernes|sabados?|domingos?)";

    private static final Pattern RANKING_QUERY = query(
            "(?:cual es )?(?:(?:mi|el) )?ranking (?:de|d) (?:mis )?rutas"
            + "|(?:(?:cual(?:es)? (?:es|son)|que|q) )?(?:mi|mis) rutas? "
            + "(?:mas (?:utilizad[oa]s?|usad[oa]s?|frecuentes?)|con mas (?:viajes completados|penalidades))"
            + "|(?:que|q) rutas? (?:he (?:utilizado|usado) mas|(?:utilizo|uso|utilice|use) mas|"
            + "tienen? mas penalidades|con mas (?:viajes completados|penalidades))"
            + "|(?:donde|en que rutas?) (?:tengo|acumulo) mas penalidades"
            + "|cuantas penalidades (?:tengo|llevo) por ruta");

    private static final Pattern FREQUENCY_QUERY = query(
            "(?:cual (?:es|fue) )?(?:(?:mi|la) )?frecuencia(?: historica)? de (?:mis )?viajes"
            + "|(?:que|q) dias?(?: de la semana)? (?:suelo viajar|viajo|viaje|he viajado) (?:mas|con mas frecuencia)"
            + "|cuantos (?:" + VIAJES_REALIZADOS + "|viajes completados)(?: como (?:conductor|pasajero))? "
            + "(?:(?:el|los) " + DIAS + "|por dia)"
            + "|(?:mis )?cancelaciones (?:propias|de terceros)"
            + "|cuantos viajes (?:cancele(?: yo)?|cancelaron terceros)");

    private static final Pattern STATISTICS_QUERY = query(
            "(?:cuales son )?(?:(?:mis|las) )?estadisticas(?: (?:generales|historicas|personales))?"
            + "(?: de (?:mis )?viajes)?(?=$| (?:por favor|(?:de|en) (?:est[ae]|l[ao]s?)|del|desde|entre|como (?:conductor|pasajero))\\b)"
            + "|(?:(?:mi|el) )?resumen de mis viajes"
            + "|(?:(?:mi|el) )?total de (?:mis )?viajes (?:completados|realizados|cancelados)"
            + "|cuantos (?:" + VIAJES_REALIZADOS + "|viajes cancelados (?:tengo|llevo))"
            + "|cuantas (?:cancelaciones|penalidades)(?: acumuladas)? (?:tengo|llevo|acumule|he acumulado)");

    private final String toolName;

    AgentReportIntent(String toolName) {
        this.toolName = toolName;
    }

    public String toolName() {
        return toolName;
    }

    public static boolean isReportTool(String name) {
        return Arrays.stream(values()).anyMatch(intent -> intent.toolName.equals(name));
    }

    public static Set<AgentReportIntent> classify(String message) {
        String normalized = AgentText.sinAcentos(message);
        normalized = SPACES.matcher(normalized).replaceAll(" ");
        Set<AgentReportIntent> intents = EnumSet.noneOf(AgentReportIntent.class);
        for (String sentence : SENTENCES.split(normalized)) {
            // A negated request can govern a list of items joined by "y".
            if (sentence.stripLeading().startsWith("no ")) continue;
            for (String part : CONJUNCTION.split(sentence)) {
                String clause = part.strip();
                if (OTHER_OWNER.matcher(clause).find()) continue;
                // The more specific route and day questions take precedence within a clause.
                if (RANKING_QUERY.matcher(clause).find()) intents.add(RANKING);
                else if (FREQUENCY_QUERY.matcher(clause).find()) intents.add(FRECUENCIA);
                else if (STATISTICS_QUERY.matcher(clause).find()) intents.add(ESTADISTICAS);
            }
        }
        return intents;
    }

    private static Pattern query(String expression) {
        return Pattern.compile("^(?:(?:y|tambien|ademas) )?(?:por favor )?"
                + "(?:(?:muestrame|dime|dame|ensename|consulta(?:me)?|quiero (?:ver|saber|consultar)) )?"
                + "(?:" + expression + ")\\b");
    }
}
