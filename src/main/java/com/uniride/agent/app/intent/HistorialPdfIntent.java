package com.uniride.agent.app.intent;

import static com.uniride.agent.app.clarification.Aclaraciones.Campo.PDF_ANIO;
import static com.uniride.agent.app.clarification.Aclaraciones.Campo.PDF_DESDE;
import static com.uniride.agent.app.clarification.Aclaraciones.Campo.PDF_HASTA;
import static com.uniride.agent.app.clarification.Aclaraciones.Campo.PDF_PERIODO;
import static com.uniride.agent.app.clarification.Aclaraciones.Campo.PDF_TIPO;
import static com.uniride.agent.app.tools.ReportesAgentTools.DESCARGA_HISTORIAL_PDF;
import com.uniride.agent.app.clarification.Aclaraciones;
import com.uniride.agent.app.dto.AclaracionAgente;
import com.uniride.agent.app.dto.CampoAclaracion;
import com.uniride.agent.app.dto.OpcionAclaracion;
import com.uniride.config.TiempoConfig;
import java.text.Normalizer;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final Pattern ROL_IMPRECISO = Pattern.compile("\\b(?:solo )?(?:como|tipo)(?: [a-z]+)?");
    private static final Pattern CAMPO_RESPONDIDO = Pattern.compile("^(?:fecha inicial|desde|fecha final|hasta|tipo|rol|año|ano|periodo):\\s*(.+)$");

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

    public record Solicitud(Filtros filtros, String aclaracion, Borrador borrador) {
        public Solicitud(Filtros filtros, String aclaracion) { this(filtros, aclaracion, null); }

        public Solicitud {
            if ((filtros == null) == (aclaracion == null)) throw new IllegalArgumentException("Indica filtros o una aclaración");
        }

        public AclaracionAgente aclaracionEstructurada() {
            if (borrador == null) return null;
            if (borrador.rangoInvertido() && borrador.faltantes().equals(Set.of(PDF_DESDE, PDF_HASTA))) {
                return Aclaraciones.alternativa(DESCARGA_HISTORIAL_PDF, PDF_DESDE, PDF_HASTA);
            }
            var campos = new ArrayList<CampoAclaracion>();
            for (var campo : EnumSet.copyOf(borrador.faltantes())) {
                if (campo == PDF_PERIODO && borrador.ultimoMes()) {
                    campos.add(PDF_PERIODO.conOpciones(List.of(new OpcionAclaracion("mes pasado", "Mes calendario pasado"),
                            new OpcionAclaracion("ultimos 30 dias", "Últimos 30 días"))));
                } else if (borrador.rangoInvertido() && (campo == PDF_DESDE || campo == PDF_HASTA)) {
                    campos.add(campo.opcional("Corrige al menos una fecha para ordenar el período."));
                } else campos.add(campo.obligatorio());
            }
            return Aclaraciones.crear(DESCARGA_HISTORIAL_PDF, campos.toArray(CampoAclaracion[]::new));
        }
    }

    record Borrador(Filtros conocidos, Set<Aclaraciones.Campo> faltantes, Integer mes,
                    boolean ultimoMes, boolean rangoInvertido) {
        Borrador { faltantes = Set.copyOf(faltantes); }
    }

    public static Solicitud classify(String message, Clock clock) {
        String texto = normalizar(message);
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
                return aclarar("¿Qué período y tipo de historial quieres descargar? Indica una sola solicitud de PDF.",
                        new Filtros(null, null, null), PDF_TIPO, PDF_PERIODO);
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
        if (!TIPO_PENDIENTE.matcher(periodo).find()) return resolverPeriodo(periodo, hoy, tipo);

        var solicitud = resolverPeriodo(ROL_IMPRECISO.matcher(periodo).replaceAll(" ").strip(), hoy, null);
        var faltantes = EnumSet.of(PDF_TIPO);
        var previo = solicitud.borrador();
        if (previo != null) faltantes.addAll(previo.faltantes());
        var borrador = new Borrador(previo == null ? solicitud.filtros() : previo.conocidos(), faltantes,
                previo == null ? null : previo.mes(), previo != null && previo.ultimoMes(), previo != null && previo.rangoInvertido());
        return new Solicitud(null,
                "¿Quieres el historial como conductor, como pasajero o de ambos roles? Indica el tipo al pedir el PDF.", borrador);
    }

    public static Solicitud continuar(String mensaje, Solicitud pendiente, Clock clock) {
        if (pendiente == null || pendiente.borrador() == null) return null;
        String texto = PUNTUACION.matcher(normalizar(mensaje).strip()).replaceAll("").strip();
        var campo = CAMPO_RESPONDIDO.matcher(texto);
        if (campo.matches()) {
            String id = switch (texto.substring(0, texto.indexOf(':')).strip()) {
                case "fecha inicial", "desde" -> "desde";
                case "fecha final", "hasta" -> "hasta";
                case "tipo", "rol" -> "tipo";
                case "ano" -> "anio";
                default -> "periodo";
            };
            return continuarCampos(Map.of(id, campo.group(1)), pendiente, clock);
        }

        var faltantes = pendiente.borrador().faltantes();
        if (faltantes.contains(PDF_ANIO) && texto.matches("(?:de )?\\d{4}")) {
            return continuarCampos(Map.of("anio", texto.replaceFirst("^de ", "")), pendiente, clock);
        }
        if (faltantes.contains(PDF_TIPO) && tipoFormulario(texto) != null) {
            return continuarCampos(Map.of("tipo", texto), pendiente, clock);
        }
        if (faltantes.contains(PDF_PERIODO) && (MES_ANIO.matcher(texto).matches() || MES_SOLO.matcher(texto).matches()
                || RANGOS.stream().anyMatch(rango -> rango.matcher(texto).matches()) || LIMITE.matcher(texto).matches()
                || List.of("mes pasado", "mes calendario pasado", "este mes", "ultimos 30 dias", "todo", "todo mi historial").contains(texto))) {
            return continuarCampos(Map.of("periodo", texto), pendiente, clock);
        }
        var limite = LIMITE.matcher(texto);
        if (limite.matches()) return continuarCampos(Map.of(limite.group(1), limite.group(2)), pendiente, clock);
        if (FECHA_ISO.matcher(texto).matches() || FECHA_ES.matcher(texto).matches()) {
            if (faltantes.contains(PDF_DESDE) && !faltantes.contains(PDF_HASTA)) {
                return continuarCampos(Map.of("desde", texto), pendiente, clock);
            }
            if (faltantes.contains(PDF_HASTA) && !faltantes.contains(PDF_DESDE)) {
                return continuarCampos(Map.of("hasta", texto), pendiente, clock);
            }
        }
        return null;
    }

    public static Solicitud continuarCampos(Map<String, String> campos, Solicitud pendiente, Clock clock) {
        if (pendiente == null || pendiente.borrador() == null || campos.isEmpty()
                || campos.keySet().stream().anyMatch(id -> pendiente.aclaracionEstructurada().campos().stream()
                .noneMatch(campo -> campo.id().equals(id)))) return null;

        Borrador original = pendiente.borrador();
        var faltantes = EnumSet.copyOf(original.faltantes());
        Filtros conocidos = original.conocidos();
        LocalDate desde = conocidos.desde();
        LocalDate hasta = conocidos.hasta();
        String tipo = conocidos.tipo();
        String mensaje = pendiente.aclaracion();
        Integer mes = original.mes();
        boolean ultimoMes = original.ultimoMes();
        boolean rangoInvertido = original.rangoInvertido();
        if (campos.containsKey("tipo")) {
            tipo = tipoFormulario(normalizar(campos.get("tipo")));
            if (tipo != null) faltantes.remove(PDF_TIPO);
        }
        if (campos.containsKey("periodo")) {
            var nuevo = resolverPeriodo(normalizar(campos.get("periodo")).strip(), LocalDate.now(clock.withZone(TiempoConfig.LIMA)), tipo);
            faltantes.remove(PDF_PERIODO);
            if (nuevo.borrador() == null) {
                desde = nuevo.filtros().desde();
                hasta = nuevo.filtros().hasta();
            } else {
                faltantes.addAll(nuevo.borrador().faltantes());
                desde = nuevo.borrador().conocidos().desde();
                hasta = nuevo.borrador().conocidos().hasta();
                mes = nuevo.borrador().mes();
                ultimoMes = nuevo.borrador().ultimoMes();
                rangoInvertido = nuevo.borrador().rangoInvertido();
                mensaje = nuevo.aclaracion();
            }
        }
        if (campos.containsKey("anio") && campos.get("anio").matches("[0-9]{1,4}") && mes != null) {
            int anio = Integer.parseInt(campos.get("anio"));
            if (anio > 0) {
                YearMonth periodo = YearMonth.of(anio, mes);
                desde = periodo.atDay(1);
                hasta = periodo.atEndOfMonth();
                faltantes.remove(PDF_ANIO);
            }
        }
        if (campos.containsKey("desde")) {
            LocalDate valor = fechaValida(normalizar(campos.get("desde")));
            if (valor != null) {
                desde = valor;
                faltantes.remove(PDF_DESDE);
            }
        }
        if (campos.containsKey("hasta")) {
            LocalDate valor = fechaValida(normalizar(campos.get("hasta")));
            if (valor != null) {
                hasta = valor;
                faltantes.remove(PDF_HASTA);
            }
        }
        if (desde != null && hasta != null) {
            if (desde.isAfter(hasta)) return rangoInvertido(desde, hasta, tipo, faltantes);
            if (rangoInvertido) faltantes.removeAll(Set.of(PDF_DESDE, PDF_HASTA));
        }
        if (faltantes.isEmpty()) return listo(desde, hasta, tipo);
        if (faltantes.equals(EnumSet.of(PDF_TIPO))) {
            mensaje = "¿Quieres el historial como conductor, como pasajero o de ambos roles?";
        }
        return new Solicitud(null, mensaje, new Borrador(new Filtros(desde, hasta, tipo),
                faltantes, mes, ultimoMes, rangoInvertido));
    }

    private static Solicitud resolverPeriodo(String periodo, LocalDate hoy, String tipo) {
        for (Pattern forma : RANGOS) {
            Matcher rango = forma.matcher(periodo);
            if (rango.matches()) {
                LocalDate hasta = fechaValida(rango.group(2));
                String inicio = rango.group(1);
                LocalDate desde = inicio.length() <= 2 ? diaDelMes(hasta, inicio) : fechaValida(inicio);
                if (desde == null || hasta == null) {
                    var faltantes = EnumSet.noneOf(Aclaraciones.Campo.class);
                    if (desde == null) faltantes.add(PDF_DESDE);
                    if (hasta == null) faltantes.add(PDF_HASTA);
                    return fechasInvalidas(desde, hasta, tipo, faltantes);
                }
                if (desde.isAfter(hasta)) return rangoInvertido(desde, hasta, tipo, EnumSet.noneOf(Aclaraciones.Campo.class));
                return listo(desde, hasta, tipo);
            }
        }
        Matcher limite = LIMITE.matcher(periodo);
        if (limite.matches()) {
            LocalDate fecha = fechaValida(limite.group(2));
            if (fecha == null) return fechasInvalidas(null, null, tipo,
                    EnumSet.of(limite.group(1).equals("desde") ? PDF_DESDE : PDF_HASTA));
            return limite.group(1).equals("desde") ? listo(fecha, null, tipo) : listo(null, fecha, tipo);
        }

        String expresion = PREFIJO_PERIODO.matcher(periodo).replaceFirst("");
        return switch (expresion) {
            case "", "todo", "todo mi historial" -> listo(null, null, tipo);
            case "mes pasado", "mes calendario pasado" -> mesCompleto(YearMonth.from(hoy).minusMonths(1), tipo);
            case "este mes" -> listo(hoy.withDayOfMonth(1), hoy, tipo);
            case "ultimos 30 dias" -> listo(hoy.minusDays(29), hoy, tipo);
            case "ultimo mes" -> new Solicitud(null,
                    "¿Te refieres al mes calendario pasado o a los últimos 30 días? Indícalo al pedir el PDF.",
                    new Borrador(new Filtros(null, null, tipo), Set.of(PDF_PERIODO), null, true, false));
            default -> resolverMes(expresion, hoy, tipo);
        };
    }

    private static Solicitud resolverMes(String periodo, LocalDate hoy, String tipo) {
        Matcher mes = MES_ANIO.matcher(periodo);
        if (mes.matches()) {
            String anio = mes.group(2);
            int numeroAnio = anio == null ? hoy.getYear() - 1 : anio.equals("este ano") ? hoy.getYear() : Integer.parseInt(anio);
            if (numeroAnio < 1) return faltaAnio(tipo, numeroMes(mes.group(1)));
            return mesCompleto(YearMonth.of(numeroAnio, numeroMes(mes.group(1))), tipo);
        }
        var mesSolo = MES_SOLO.matcher(periodo);
        if (mesSolo.matches()) return faltaAnio(tipo, numeroMes(mesSolo.group(1)));
        return aclarar("¿Qué período quieres descargar? Indica un mes con su año, fechas concretas o todo tu historial al pedir el PDF.",
                new Filtros(null, null, tipo), PDF_PERIODO);
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

    private static Solicitud faltaAnio(String tipo, int mes) {
        return new Solicitud(null, "¿De qué año quieres descargar ese mes? Indica el mes y el año al pedir el PDF.",
                new Borrador(new Filtros(null, null, tipo), Set.of(PDF_ANIO), mes, false, false));
    }

    private static Solicitud fechasInvalidas(LocalDate desde, LocalDate hasta, String tipo, Set<Aclaraciones.Campo> campos) {
        return new Solicitud(null, "La solicitud contiene una fecha inválida. Vuelve a pedir el PDF con fechas válidas.",
                new Borrador(new Filtros(desde, hasta, tipo), campos, null, false, false));
    }

    private static Solicitud rangoInvertido(LocalDate desde, LocalDate hasta, String tipo, Set<Aclaraciones.Campo> otros) {
        var campos = EnumSet.of(PDF_DESDE, PDF_HASTA);
        campos.addAll(otros);
        return new Solicitud(null, "La fecha inicial es posterior a la fecha final. Vuelve a pedir el PDF con el rango corregido.",
                new Borrador(new Filtros(desde, hasta, tipo), campos, null, false, true));
    }

    private static Solicitud aclarar(String mensaje, Filtros filtros, Aclaraciones.Campo... campos) {
        return new Solicitud(null, mensaje, new Borrador(filtros, Set.of(campos), null, false, false));
    }

    private static LocalDate fechaValida(String valor) {
        try { return fecha(valor); }
        catch (DateTimeException invalida) { return null; }
    }

    private static LocalDate diaDelMes(LocalDate fecha, String dia) {
        if (fecha == null) return null;
        try { return fecha.withDayOfMonth(Integer.parseInt(dia)); }
        catch (DateTimeException invalida) { return null; }
    }

    private static String tipoFormulario(String valor) {
        return switch (valor.strip()) {
            case "conductor", "conductora" -> "conductor";
            case "pasajero", "pasajera" -> "pasajero";
            case "todos", "ambos roles", "ambos" -> "todos";
            default -> null;
        };
    }

    private static String normalizar(String mensaje) {
        String texto = ACENTOS.matcher(Normalizer.normalize(mensaje, Normalizer.Form.NFD)).replaceAll("")
                .toLowerCase(Locale.ROOT);
        return ESPACIOS.matcher(texto).replaceAll(" ");
    }
}
