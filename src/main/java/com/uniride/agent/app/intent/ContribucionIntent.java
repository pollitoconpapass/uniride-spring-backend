package com.uniride.agent.app.intent;

import static com.uniride.agent.app.tools.ContribucionAgentTools.NOMBRE;
import com.uniride.agent.app.clarification.Aclaraciones;
import com.uniride.agent.app.dto.AclaracionAgente;
import com.uniride.agent.app.dto.CampoAclaracion;
import com.uniride.agent.app.dto.ContribucionFiltros;
import com.uniride.agent.app.dto.ContribucionResultado;
import com.uniride.config.TiempoConfig;
import java.text.Normalizer;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts only filters expressed by the passenger. The provider does not supply query parameters. */
public final class ContribucionIntent {
    private static final Pattern CONSULTA = Pattern.compile("\\b(?:cuant[oa]s?\\b.{0,70}\\b"
            + "(?:aport\\w*|pag\\w*|cost\\w*|cuest\\w*|val(?:e|dria)|contribu\\w*|gasolina|litros?|mi parte)|"
            + "que (?:contribucion|aporte)\\b|que favor\\b.{0,65}\\b(?:ofrecer|compensar|dar|aportar|sugieres)|"
            + "(?:sugerencia|estimacion) de (?:contribucion|aporte)\\b|"
            + "(?:calcul\\w*|sugi\\w*|estim\\w*)\\b.{0,45}\\b(?:contribucion|aporte)|"
            + "(?:aporte|contribucion) (?:estimad[oa]|referencial)|(?:que|cual) seria mi parte|"
            + "cual (?:es|seria) (?:el )?(?:precio|costo|importe|monto|aporte)|cuanto (?:es|seria|me toca)\\b)\\b");
    private static final Pattern PAGO_DE_VIAJE = Pattern.compile("\\b(?:viajes?|pasajeros?|cupo|gasolina|compensacion|"
            + "contribucion|aporte|aportar|contribuir)\\b");
    private static final Pattern OTRO_PEDIDO = Pattern.compile("^(?:(?:ahora|mejor|por favor),? )?(?:(?:quiero|necesito|puedes|podrias) )?"
            + "(?:no|nunca|olvida|cambiemos|registra\\w*|agrega\\w*|anade\\w*|edita\\w*|modifica\\w*|"
            + "actualiza\\w*|activa\\w*|desactiva\\w*|elimina\\w*|borra\\w*|crea\\w*|publica\\w*|"
            + "cancela\\w*|reprograma\\w*|unirme|uneme|sumarme|subirme|solicita\\w*|reserva\\w*|"
            + "(?:como|dime como) (?:registrar|editar|pagar|solicitar|reservar))\\b");
    private static final Pattern REFERENCIA = Pattern.compile("\\b(?:ese viaje|en ese|para ese|del anterior|"
            + "del mismo|el mismo viaje)\\b");
    private static final Pattern ELIPSIS = Pattern.compile("\\b(?:mi parte|si (?:vamos|somos)|cuanta gasolina|que favor|cuanto me toca)\\b");
    private static final Pattern PROPIO = Pattern.compile("\\b(?:mi viaje|mis viajes)\\b");
    private static final String LUGAR = "([\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N} .’'()/%_-]{0,254}?)";
    private static final String FIN_FILTRO = "(?=$|[?!,;]|\\.$| (?:a las? |con |para |del \\d|"
            + "el (?:\\d|lunes|martes|mi[eé]rcoles|jueves|viernes|s[aá]bado|domingo)|hoy\\b|ma[nñ]ana\\b|"
            + "pasado ma[nñ]ana\\b|por favor\\b|si (?:vamos|somos)\\b))";
    private static final Pattern RUTA = literal("\\b(?:desde|de) " + LUGAR
            + " (?:hacia|hasta|a) (?:la |el )?(?!las?\\b)" + LUGAR + FIN_FILTRO);
    private static final Pattern ORIGEN = literal("\\b(?:origen(?:[: ]+)|desde )" + LUGAR + FIN_FILTRO);
    private static final Pattern DESTINO = literal("\\b(?:destino(?:[: ]+)|hacia )" + LUGAR + FIN_FILTRO);
    private static final String NOMBRE_CONDUCTOR = "([\\p{L}][\\p{L}\\p{M} .’'-]{0,119}?)";
    private static final Pattern CONDUCTOR = literal("\\bcon (?:el conductor |la conductora )" + NOMBRE_CONDUCTOR + FIN_FILTRO);
    private static final Pattern RESPUESTA_CONDUCTOR = literal("^con " + NOMBRE_CONDUCTOR + FIN_FILTRO);
    private static final List<String> MESES = List.of("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
            "agosto", "septiembre", "octubre", "noviembre", "diciembre");
    private static final Pattern FECHA = Pattern.compile("\\b(?:(\\d{4}-\\d{2}-\\d{2})|(\\d{1,2}) de ("
            + String.join("|", MESES) + "|setiembre)(?: (?:de |del )?(\\d{4}))?"
            + "|(\\d{1,2})/(\\d{1,2})(?:/(\\d{4}))?)\\b");
    private static final Pattern FECHA_INCOMPLETA = Pattern.compile("\\b(?:el|del|dia) \\d{1,2}\\b");
    private static final Pattern SOLO_DIA = Pattern.compile("\\d{1,2}");
    private static final String DIAS = "lunes|martes|miercoles|jueves|viernes|sabado|domingo";
    private static final Pattern DIA = Pattern.compile("\\b(" + DIAS + ")\\b");
    private static final Pattern DIA_RELATIVO = Pattern.compile("\\b(?:este|proximo|siguiente) (?:" + DIAS + ")\\b");
    private static final Pattern FECHA_RELATIVA = Pattern.compile("\\b(pasado manana|manana|hoy)\\b");
    private static final List<String> NUMEROS = List.of("cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete",
            "ocho", "nueve", "diez", "once", "doce", "trece", "catorce", "quince", "dieciseis", "diecisiete",
            "dieciocho", "diecinueve", "veinte", "veintiuno", "veintidos", "veintitres");
    private static final String PARTE_DIA = "(?:de la (manana|tarde|noche|madrugada)|(a|p)\\.? *m\\.?)";
    private static final Pattern HORA = Pattern.compile("\\b(?:a las? (\\d{1,2}|una|" + String.join("|", NUMEROS)
            + ")(?::(\\d{2})| y (media|cuarto))?|(\\d{1,2}):(\\d{2}))(?![\\d:])(?: " + PARTE_DIA + ")?\\b\\.?");
    private static final Pattern HORA_INCOMPLETA = Pattern.compile("\\ba las?\\b");
    private static final Pattern FRACCION_HORA_NO_RECONOCIDA = Pattern.compile("\\b(?:a las?|horas?) (?:\\d{1,2}|"
            + String.join("|", NUMEROS) + ") (?:menos|y (?!media\\b|cuarto\\b)(?=\\d|"
            + String.join("|", NUMEROS) + "\\b))");
    private static final String CANTIDAD = "(?:-?\\d+|un|una|" + String.join("|", NUMEROS) + ")";
    private static final Pattern PASAJEROS = Pattern.compile("(?<![\\p{L}\\d.,-])(" + CANTIDAD
            + ") pasajeros?\\b|\\b(?:vamos|somos|seriamos|seremos|para|con) (" + CANTIDAD + ")(?=[.!?,;]*$)");
    private static final Pattern SOLO_CANTIDAD = Pattern.compile(CANTIDAD);
    private static final Pattern RESPUESTA_FILTRO = Pattern.compile("^(?:(?:(?:al|el|para el) (?:del? )?|del |dia )"
            + "(?=\\d|(?:este |proximo |siguiente )?(?:" + DIAS + ")\\b|hoy\\b|manana\\b)|"
            + "(?:el|al) de |a las? |desde |hacia |origen[: ]|destino[: ]|con |"
            + "somos |vamos |seriamos |seremos |hoy\\b|manana\\b|pasado manana\\b|"
            + "(?:" + DIAS + ")\\b|" + CANTIDAD + "(?:\\b|:))");
    private static final Pattern DISYUNCION = Pattern.compile("\\b(?:o|u) (?=\\d|(?:" + DIAS
            + ")\\b|a las? |hoy\\b|manana\\b|(?:" + CANTIDAD + ") pasajeros?\\b)");
    private static final Pattern CAMPO_RESPONDIDO = Pattern.compile(
            "^(origen|destino|fecha|hora|cantidad de pasajeros|pasajeros|nombre del conductor|conductor):\\s*(.+)$");
    private static final Set<String> CAMPOS = Set.of("origen", "destino", "fecha", "hora", "pasajeros", "conductor");

    private ContribucionIntent() { }

    public record Borrador(ContribucionFiltros filtros, ContribucionResultado.Estado estado) { }

    public record Solicitud(ContribucionFiltros filtros, String aclaracion, AclaracionAgente aclaracionEstructurada) {
        Solicitud(ContribucionFiltros filtros, String aclaracion) {
            this(filtros, aclaracion, null);
        }

        public Borrador siguiente(ContribucionResultado resultado) {
            var sugerencia = resultado.sugerencia();
            ContribucionFiltros seleccionado = filtros;
            if (sugerencia != null) {
                seleccionado = viaje(sugerencia.origen(), sugerencia.destino(), sugerencia.fecha(), sugerencia.hora(),
                        filtros.conductor());
            } else if (resultado.estado() == ContribucionResultado.Estado.FALTAN_PASAJEROS
                    && resultado.alternativas().size() == 1) {
                var opcion = resultado.alternativas().getFirst();
                seleccionado = viaje(opcion.origen(), opcion.destino(), opcion.fecha(), opcion.hora(), opcion.conductor());
            }
            return new Borrador(seleccionado, resultado.estado());
        }

        private ContribucionFiltros viaje(String origen, String destino, LocalDate fecha, LocalTime hora, String conductor) {
            return new ContribucionFiltros(origen, destino, null, fecha, hora,
                    filtros.pasajeros(), conductor, filtros.viajePropio());
        }
    }

    public static Solicitud classify(String message, Borrador borrador, Clock clock) {
        String original = Normalizer.normalize(message, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
        String text = Normalizer.normalize(original, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("^[¿¡]+\\s*|[.!?…]+$", "");
        if (OTRO_PEDIDO.matcher(text).find()) return null;
        if (borrador != null && borrador.estado() != ContribucionResultado.Estado.SUGERIDA) {
            var campo = CAMPO_RESPONDIDO.matcher(text);
            if (campo.matches()) {
                String id = switch (campo.group(1)) {
                    case "nombre del conductor" -> "conductor";
                    case "cantidad de pasajeros" -> "pasajeros";
                    default -> campo.group(1);
                };
                return continuarCampos(Map.of(id, original.substring(original.indexOf(':') + 1).strip()), borrador, clock);
            }
        }
        boolean consulta = CONSULTA.matcher(text).find();
        boolean referencia = consulta && REFERENCIA.matcher(text).find();
        boolean elipsis = consulta && ELIPSIS.matcher(text).find();
        boolean seguimiento = borrador != null && (referencia || elipsis || (!consulta
                && borrador.estado() != ContribucionResultado.Estado.SUGERIDA && RESPUESTA_FILTRO.matcher(text).find()));
        if (!consulta && !seguimiento) return null;
        if (consulta && !seguimiento && !PAGO_DE_VIAJE.matcher(text).find()) return null;

        ContribucionFiltros anterior = seguimiento ? borrador.filtros() : null;
        var ruta = RUTA.matcher(original);
        boolean tieneRuta = ruta.find();
        String origen = tieneRuta ? ruta.group(1).strip() : grupo(ORIGEN, original);
        String destino = tieneRuta ? ruta.group(2).strip() : grupo(DESTINO, original);
        String sinRuta = textoSinRuta(original);
        String calendario = HORA.matcher(sinRuta).replaceAll(" ");
        var invalidos = EnumSet.noneOf(Aclaraciones.Campo.class);
        var pasajeros = validar(Aclaraciones.Campo.PASAJEROS, invalidos, () -> pasajeros(text, borrador));
        String conductor = grupo(CONDUCTOR, original);
        if (conductor == null && seguimiento && pasajeros == null) conductor = grupo(RESPUESTA_CONDUCTOR, original);
        LocalTime hora = validar(Aclaraciones.Campo.HORA, invalidos, () -> hora(sinRuta));
        LocalDate fecha = validar(Aclaraciones.Campo.FECHA, invalidos,
                () -> fecha(calendario, LocalDate.now(clock.withZone(TiempoConfig.LIMA))));
        String dia = validar(Aclaraciones.Campo.FECHA, invalidos, () -> unico(DIA, calendario));
        String sinFecha = FECHA.matcher(calendario).replaceAll(" ").strip();
        if (FECHA_INCOMPLETA.matcher(sinFecha).find() || (pasajeros == null && SOLO_DIA.matcher(sinFecha).matches())
                || (fecha == null && DIA_RELATIVO.matcher(calendario).find())) invalidos.add(Aclaraciones.Campo.FECHA);
        if (HORA_INCOMPLETA.matcher(calendario).find() || FRACCION_HORA_NO_RECONOCIDA.matcher(sinRuta).find()) {
            invalidos.add(Aclaraciones.Campo.HORA);
        }
        if (DISYUNCION.matcher(text).find()) {
            if (DISYUNCION.matcher(calendario).find()) {
                invalidos.add(PASAJEROS.matcher(calendario).find() ? Aclaraciones.Campo.PASAJEROS : Aclaraciones.Campo.FECHA);
            } else invalidos.add(Aclaraciones.Campo.HORA);
        }
        boolean tieneFiltros = origen != null || destino != null || conductor != null
                || fecha != null || dia != null || hora != null;
        if (consulta && !referencia && (tieneFiltros || !invalidos.isEmpty())) anterior = null;
        boolean viajePropio = !tieneFiltros && PROPIO.matcher(text).find();
        var filtros = new ContribucionFiltros(origen, destino, dia,
                invalidos.contains(Aclaraciones.Campo.FECHA) ? null : fecha,
                invalidos.contains(Aclaraciones.Campo.HORA) ? null : hora,
                invalidos.contains(Aclaraciones.Campo.PASAJEROS) ? null : pasajeros, conductor, viajePropio);
        filtros = anterior == null ? filtros : combinar(anterior, filtros);
        if (!invalidos.isEmpty()) return corregir(filtros, invalidos);
        return !consulta && !tieneFiltros && pasajeros == null ? null : new Solicitud(filtros, null);
    }

    public static Solicitud continuarCampos(Map<String, String> campos, Borrador borrador, Clock clock) {
        if (borrador == null || borrador.estado() == ContribucionResultado.Estado.SUGERIDA || campos.isEmpty()
                || !CAMPOS.containsAll(campos.keySet())) return null;
        if (borrador.estado() == ContribucionResultado.Estado.FALTAN_PASAJEROS
                && !campos.keySet().equals(Set.of("pasajeros"))) return null;

        var invalidos = EnumSet.noneOf(Aclaraciones.Campo.class);
        LocalDate fecha = campos.containsKey("fecha") ? validar(Aclaraciones.Campo.FECHA, invalidos,
                () -> fecha(campos.get("fecha"), LocalDate.now(clock.withZone(TiempoConfig.LIMA)))) : null;
        LocalTime hora = campos.containsKey("hora") ? validar(Aclaraciones.Campo.HORA, invalidos,
                () -> hora("a las " + campos.get("hora"))) : null;
        Integer pasajeros = campos.containsKey("pasajeros")
                ? validar(Aclaraciones.Campo.PASAJEROS, invalidos, () -> numero(campos.get("pasajeros"))) : null;
        if (campos.containsKey("fecha") && fecha == null) invalidos.add(Aclaraciones.Campo.FECHA);
        if (campos.containsKey("hora") && hora == null) invalidos.add(Aclaraciones.Campo.HORA);
        if (pasajeros != null && pasajeros < 1) {
            pasajeros = null;
            invalidos.add(Aclaraciones.Campo.PASAJEROS);
        }
        var actual = new ContribucionFiltros(campos.get("origen"), campos.get("destino"), null, fecha, hora,
                pasajeros, campos.get("conductor"), false);
        var combinado = combinar(borrador.filtros(), actual);
        return invalidos.isEmpty() ? new Solicitud(combinado, null) : corregir(combinado, invalidos);
    }

    private static Solicitud corregir(ContribucionFiltros filtros, Set<Aclaraciones.Campo> invalidos) {
        String mensaje = invalidos.size() != 1 ? "Revisa los datos indicados para calcular la sugerencia."
                : switch (invalidos.iterator().next()) {
                    case FECHA -> "Indica la fecha exacta de esa salida para calcular la sugerencia.";
                    case HORA -> "Indica la hora exacta de la salida en formato de 24 horas.";
                    case PASAJEROS -> "Indica una cantidad válida de pasajeros para calcular la sugerencia.";
                    default -> throw new IllegalStateException("Campo de contribución no admitido");
                };
        return new Solicitud(filtros, mensaje, Aclaraciones.crear(NOMBRE,
                invalidos.stream().map(Aclaraciones.Campo::obligatorio).toArray(CampoAclaracion[]::new)));
    }

    private static <T> T validar(Aclaraciones.Campo campo, Set<Aclaraciones.Campo> invalidos,
                                  java.util.function.Supplier<T> parsear) {
        try {
            return parsear.get();
        } catch (DateTimeException | NumberFormatException invalido) {
            invalidos.add(campo);
            return null;
        }
    }

    private static String textoSinRuta(String texto) {
        String sinRuta = RUTA.matcher(texto).replaceAll(" ");
        sinRuta = ORIGEN.matcher(sinRuta).replaceAll(" ");
        sinRuta = DESTINO.matcher(sinRuta).replaceAll(" ");
        return Normalizer.normalize(sinRuta, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).strip();
    }

    private static ContribucionFiltros combinar(ContribucionFiltros anterior, ContribucionFiltros actual) {
        boolean nuevoDia = actual.dia() != null || actual.fecha() != null;
        return new ContribucionFiltros(valor(actual.origen(), anterior.origen()), valor(actual.destino(), anterior.destino()),
                nuevoDia ? actual.dia() : anterior.dia(), nuevoDia ? actual.fecha() : anterior.fecha(),
                valor(actual.hora(), anterior.hora()), valor(actual.pasajeros(), anterior.pasajeros()),
                valor(actual.conductor(), anterior.conductor()), anterior.viajePropio() || actual.viajePropio());
    }

    private static <T> T valor(T actual, T anterior) { return actual == null ? anterior : actual; }

    private static String grupo(Pattern pattern, String source) {
        Matcher match = pattern.matcher(source);
        return match.find() ? match.group(1).strip() : null;
    }

    private static String unico(Pattern pattern, String source) {
        Matcher match = pattern.matcher(source);
        if (!match.find()) return null;
        String resultado = match.group();
        while (match.find()) {
            if (!resultado.equals(match.group())) throw new DateTimeException("Filtros múltiples");
        }
        return resultado;
    }

    public static String sinHoras(String texto) { return HORA.matcher(texto).replaceAll(" "); }

    public static LocalDate fecha(String text, LocalDate hoy) {
        Matcher fecha = FECHA.matcher(text);
        String relativa = unico(FECHA_RELATIVA, text);
        if (!fecha.find()) {
            if (relativa == null || relativa.equals("hoy")) return relativa == null ? null : hoy;
            return hoy.plusDays(relativa.equals("manana") ? 1 : 2);
        }
        LocalDate resultado;
        if (fecha.group(1) != null) {
            resultado = LocalDate.parse(fecha.group(1));
        } else if (fecha.group(2) != null) {
            String mes = fecha.group(3).equals("setiembre") ? "septiembre" : fecha.group(3);
            int anio = fecha.group(4) == null ? hoy.getYear() : Integer.parseInt(fecha.group(4));
            resultado = LocalDate.of(anio, MESES.indexOf(mes) + 1, Integer.parseInt(fecha.group(2)));
        } else {
            int anio = fecha.group(7) == null ? hoy.getYear() : Integer.parseInt(fecha.group(7));
            resultado = LocalDate.of(anio, Integer.parseInt(fecha.group(6)), Integer.parseInt(fecha.group(5)));
        }
        if (fecha.find() || relativa != null) throw new DateTimeException("Fechas múltiples");
        return resultado;
    }

    public static LocalTime hora(String text) {
        Matcher hora = HORA.matcher(text);
        if (!hora.find()) return null;
        int horas = numero(valor(hora.group(1), hora.group(4)));
        String minutos = valor(hora.group(2), hora.group(5));
        String fraccion = hora.group(3);
        String periodo = hora.group(6);
        String amPm = hora.group(7);
        if (periodo != null || amPm != null) {
            if (horas < 1 || horas > 12) throw new DateTimeException("Hora ambigua");
            boolean tarde = "p".equals(amPm) || "tarde".equals(periodo) || "noche".equals(periodo);
            horas = horas % 12 + (tarde ? 12 : 0);
        }
        if (hora.find()) throw new DateTimeException("Horas múltiples");
        int minuto = minutos == null ? 0 : Integer.parseInt(minutos);
        if (fraccion != null) minuto = fraccion.equals("media") ? 30 : 15;
        return LocalTime.of(horas, minuto);
    }

    private static Integer pasajeros(String text, Borrador borrador) {
        Matcher match = PASAJEROS.matcher(text);
        String cantidad = match.find() ? valor(match.group(1), match.group(2)) : null;
        if (match.find()) throw new NumberFormatException("Cantidades múltiples");
        if (cantidad == null && borrador != null && borrador.estado() == ContribucionResultado.Estado.FALTAN_PASAJEROS
                && SOLO_CANTIDAD.matcher(text).matches()) cantidad = text;
        return cantidad == null ? null : numero(cantidad);
    }

    private static int numero(String valor) {
        if (valor.equals("un") || valor.equals("una")) return 1;
        int numero = NUMEROS.indexOf(valor);
        return numero < 0 ? Integer.parseInt(valor) : numero;
    }

    private static Pattern literal(String expression) {
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
