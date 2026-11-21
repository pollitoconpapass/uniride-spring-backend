package com.uniride.agent;

import com.uniride.config.TiempoConfig;
import java.text.Normalizer;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves explicit requests for a personal history download from the current user message. */
public final class HistorialPdfIntent {
    private static final String HISTORIAL = "(?:todo )?(?:(?:mi|el) )?historial(?: de viajes)?";
    private static final String HISTORIAL_O_VIAJES = "(?:" + HISTORIAL + "|(?:todos )?mis viajes)";
    private static final String PDF_HISTORIAL = "(?:un )?(?:pdf|reporte en pdf) (?:con|de) " + HISTORIAL_O_VIAJES;
    private static final String EN_PDF = "(?= (?:.* )?en pdf\\b)";
    private static final String PEDIDO = "(?:(?:y|tambien|ademas) )?(?:por favor )?"
            + "(?:(?:quiero|quisiera|necesito|podrias|puedes) )?";
    private static final Pattern DESCARGA = Pattern.compile("^" + PEDIDO
            + "(?:descarga(?:me|r)?|exporta(?:me|r)?) (?:en pdf )?(?:" + PDF_HISTORIAL + "|" + HISTORIAL_O_VIAJES + ")\\b");
    private static final Pattern GENERACION = Pattern.compile("^" + PEDIDO
            + "(?:(?:genera(?:me|r)?|prepara(?:me|r)?) (?:" + PDF_HISTORIAL + "|" + HISTORIAL_O_VIAJES + EN_PDF
            + "|" + HISTORIAL + ")"
            + "|(?:dame|darme) (?:" + PDF_HISTORIAL + "|" + HISTORIAL_O_VIAJES + EN_PDF + "|" + HISTORIAL
            + "(?! de viajes\\b)(?= (?:de|del|desde|hasta|entre) ))"
            + "|(?:quiero|quisiera|necesito) (?:" + PDF_HISTORIAL + "|" + HISTORIAL_O_VIAJES + EN_PDF + "))\\b");
    private static final Pattern REPORTE_PDF = Pattern.compile("^(?:(?:y|tambien|ademas) )?(?:por favor )?"
            + "(?:quiero|quisiera|necesito) (?:un )?(?:reporte en pdf|pdf)"
            + "(?= (?:de|del|con|desde|hasta|entre|en) )");
    private static final String OTRA_PETICION = "(?:cuant[oa]s?|cual(?:es)?|que|dime|dame|muestrame|consulta|"
            + "descarga(?:me|r)?|exporta(?:me|r)?|genera(?:me|r)?|prepara(?:me|r)?|quiero|quisiera|necesito|"
            + "elimina|borra|cancela|crea)\\b";
    private static final Pattern CLAUSULAS = Pattern.compile("(?:[.!?,;\\r\\n]+\\s*[¿¡]?\\s*| +y +)"
            + "(?=(?:(?:tambien|ademas|y) )?" + OTRA_PETICION + ")");
    private static final Pattern PUNTUACION = Pattern.compile("^[¿¡]\\s*|[.!?,;]+$");
    private static final Pattern OTRO_USUARIO = Pattern.compile("\\b(?:de (?:otr[oa]s?|tod[oa]s? l[oa]s) "
            + "(?:usuari[oa]s?|conductor(?:es)?|pasajer[oa]s?|personas?)|usuario_?id|user_?id)\\b");
    private static final Pattern TIPO = Pattern.compile("\\b(?:solo )?(?:como|tipo) (conductor(?:a)?|pasajer[oa]|todos)\\b");
    private static final Pattern TIPO_PENDIENTE = Pattern.compile("\\b(?:como|tipo)\\b");

    private static final List<String> MESES = List.of("enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre");
    private static final String MES = "(" + String.join("|", MESES) + "|setiembre)";
    private static final Pattern MES_ANIO = Pattern.compile(MES + " (?:de (\\d{4}|este ano)|del ano pasado)");
    private static final Pattern MES_SOLO = Pattern.compile(MES);
    private static final Pattern FECHA_ES = Pattern.compile("(\\d{1,2}) de " + MES + " de (\\d{4})");
    private static final String FECHA = "(?:\\d{4}-\\d{2}-\\d{2}|\\d{1,2} de (?:" + String.join("|", MESES)
            + "|setiembre) de \\d{4})";
    private static final String INICIO_RANGO = "(" + FECHA + "|\\d{1,2})";
    private static final List<Pattern> RANGOS = List.of(
            Pattern.compile("del " + INICIO_RANGO + " al (" + FECHA + ")"),
            Pattern.compile("entre (?:el )?" + INICIO_RANGO + " y (?:el )?(" + FECHA + ")"),
            Pattern.compile("desde (?:el )?(" + FECHA + ") hasta (?:el )?(" + FECHA + ")"));
    private static final Pattern LIMITE = Pattern.compile("(desde|hasta) (?:el )?(" + FECHA + ")");
    private static final Pattern FECHA_ISO = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern PREFIJO_PERIODO = Pattern.compile("^(?:del |de (?:los |el )?|en (?:los |el )?)");
    private static final Pattern ACENTOS = Pattern.compile("\\p{M}+");
    private static final Pattern ESPACIOS = Pattern.compile("[\\p{Z}\\t]+");
    private static final Pattern ADJUNTOS = Pattern.compile("\\b(?:en pdf|por favor)\\b");
    private static final Pattern PAUSA = Pattern.compile("\\s*[,;]\\s*");

    private HistorialPdfIntent() { }

    public record Filtros(LocalDate desde, LocalDate hasta, String tipo) { }

    public record Solicitud(Filtros filtros, String aclaracion) {
        public Solicitud {
            if ((filtros == null) == (aclaracion == null)) throw new IllegalArgumentException("Indica filtros o una aclaración");
        }
    }

    public static Solicitud classify(String message, Clock clock) {
        String texto = ACENTOS.matcher(Normalizer.normalize(message, Normalizer.Form.NFD)).replaceAll("")
                .toLowerCase(Locale.ROOT);
        texto = ESPACIOS.matcher(texto).replaceAll(" ");
        if (OTRO_USUARIO.matcher(texto).find()) return null;

        LocalDate hoy = LocalDate.now(clock.withZone(TiempoConfig.LIMA));
        Solicitud resultado = null;
        for (String parte : CLAUSULAS.split(texto)) {
            String clausula = PUNTUACION.matcher(parte.strip()).replaceAll("").strip();
            Matcher pedido = buscarPedido(clausula);
            if (pedido == null) continue;
            String filtros = clausula.substring(pedido.end()).strip();
            Solicitud solicitud = interpretarFiltros(filtros, hoy);
            if (resultado != null && !resultado.equals(solicitud)) {
                return aclarar("¿Qué período y tipo de historial quieres descargar? Indica una sola solicitud de PDF.");
            }
            resultado = solicitud;
        }
        return resultado;
    }

    private static Matcher buscarPedido(String clausula) {
        for (Pattern forma : List.of(DESCARGA, GENERACION, REPORTE_PDF)) {
            Matcher pedido = forma.matcher(clausula);
            if (pedido.find()) return pedido;
        }
        return null;
    }

    private static Solicitud interpretarFiltros(String filtros, LocalDate hoy) {
        String periodo = ADJUNTOS.matcher(PAUSA.matcher(filtros).replaceAll(" ")).replaceAll("").strip();
        String tipo = "todos";
        Matcher rol = TIPO.matcher(periodo);
        if (rol.find()) {
            tipo = switch (rol.group(1)) {
                case "conductor", "conductora" -> "conductor";
                case "pasajero", "pasajera" -> "pasajero";
                default -> "todos";
            };
            periodo = rol.replaceFirst("");
        }
        periodo = ESPACIOS.matcher(periodo).replaceAll(" ").strip();
        if (TIPO_PENDIENTE.matcher(periodo).find()) {
            return aclarar("¿Quieres el historial como conductor, como pasajero o de ambos roles? Indica el tipo al pedir el PDF.");
        }
        try {
            return resolverPeriodo(periodo, hoy, tipo);
        } catch (DateTimeException invalida) {
            return aclarar("La solicitud contiene una fecha inválida. Vuelve a pedir el PDF con fechas válidas.");
        }
    }

    private static Solicitud resolverPeriodo(String periodo, LocalDate hoy, String tipo) {
        for (Pattern forma : RANGOS) {
            Matcher rango = forma.matcher(periodo);
            if (rango.matches()) {
                LocalDate hasta = fecha(rango.group(2));
                String inicio = rango.group(1);
                LocalDate desde = inicio.length() <= 2 ? hasta.withDayOfMonth(Integer.parseInt(inicio)) : fecha(inicio);
                if (desde.isAfter(hasta)) {
                    return aclarar("La fecha inicial es posterior a la fecha final. Vuelve a pedir el PDF con el rango corregido.");
                }
                return listo(desde, hasta, tipo);
            }
        }
        Matcher limite = LIMITE.matcher(periodo);
        if (limite.matches()) {
            LocalDate fecha = fecha(limite.group(2));
            return limite.group(1).equals("desde") ? listo(fecha, null, tipo) : listo(null, fecha, tipo);
        }

        String expresion = PREFIJO_PERIODO.matcher(periodo).replaceFirst("");
        return switch (expresion) {
            case "", "todo", "todo mi historial" -> listo(null, null, tipo);
            case "mes pasado" -> mesCompleto(YearMonth.from(hoy).minusMonths(1), tipo);
            case "este mes" -> listo(hoy.withDayOfMonth(1), hoy, tipo);
            case "ultimos 30 dias" -> listo(hoy.minusDays(29), hoy, tipo);
            case "ultimo mes" -> aclarar("¿Te refieres al mes calendario pasado o a los últimos 30 días? Indícalo al pedir el PDF.");
            default -> resolverMes(expresion, hoy, tipo);
        };
    }

    private static Solicitud resolverMes(String periodo, LocalDate hoy, String tipo) {
        Matcher mes = MES_ANIO.matcher(periodo);
        if (mes.matches()) {
            String anio = mes.group(2);
            int numeroAnio = anio == null ? hoy.getYear() - 1 : anio.equals("este ano") ? hoy.getYear() : Integer.parseInt(anio);
            return mesCompleto(YearMonth.of(numeroAnio, numeroMes(mes.group(1))), tipo);
        }
        if (MES_SOLO.matcher(periodo).matches()) {
            return aclarar("¿De qué año quieres descargar ese mes? Indica el mes y el año al pedir el PDF.");
        }
        return aclarar("¿Qué período quieres descargar? Indica un mes con su año, fechas concretas o todo tu historial al pedir el PDF.");
    }

    private static LocalDate fecha(String valor) {
        if (FECHA_ISO.matcher(valor).matches()) return LocalDate.parse(valor);
        Matcher fecha = FECHA_ES.matcher(valor);
        if (!fecha.matches()) throw new DateTimeException("Fecha inválida");
        return LocalDate.of(Integer.parseInt(fecha.group(3)), numeroMes(fecha.group(2)), Integer.parseInt(fecha.group(1)));
    }

    private static int numeroMes(String mes) {
        return MESES.indexOf(mes.equals("setiembre") ? "septiembre" : mes) + 1;
    }

    private static Solicitud mesCompleto(YearMonth mes, String tipo) {
        return listo(mes.atDay(1), mes.atEndOfMonth(), tipo);
    }

    private static Solicitud listo(LocalDate desde, LocalDate hasta, String tipo) {
        return new Solicitud(new Filtros(desde, hasta, tipo), null);
    }

    private static Solicitud aclarar(String mensaje) {
        return new Solicitud(null, mensaje);
    }
}
