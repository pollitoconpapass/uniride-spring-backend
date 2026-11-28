package com.uniride.agent.conversation;

import static com.uniride.agent.support.AgentText.normalizar;

import com.uniride.agent.confirmation.TipoOperacion;
import com.uniride.agent.dto.AclaracionAgente;
import com.uniride.agent.dto.OpcionAclaracion;
import com.uniride.enums.EstadoSolicitud;
import com.uniride.enums.Rol;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Interpreta las órdenes del conductor a partir del mensaje y de la aclaración de su sesión. */
public final class CoordinacionConductorIntent {
    private static final String DIAS = "lunes|martes|miercoles|jueves|viernes|sabado|domingo";
    private static final String RELATIVAS = "pasado manana|manana|hoy";
    private static final String PREFIJO = "^(?:(?:por favor|ya estamos saliendo|ya salimos|terminamos|"
            + "ya terminamos|llegamos)[,.: ]+)*(?:(?:quiero|necesito|puedes|podrias|ayudame a) )?(?:que )?";
    private static final String ARTICULO = "(?:(?:el|un|mi|este|ese|aquel) )?";
    private static final String SOLICITUD = "(?:(?:la|una|esa|esta|dicha) )?solicitud\\b";
    private static final String FIN_FILTRO = "(?=$|[,.!?;]| (?:a las?\\b|para\\b|del? (?:\\d|"
            + DIAS + "|" + RELATIVAS + ")\\b|(?:el |este |proximo )?(?:" + DIAS + "|" + RELATIVAS
            + ")\\b|\\d{4}-\\d{2}-\\d{2}\\b|por favor\\b))";
    private static final Pattern ORIGEN = Pattern.compile(
            "(?iu)\\b(?:origen\\s*:?\\s+|desde |viaje de |ruta de )(.+?)(?= a (?!las?\\b)| hacia |"
                    + FIN_FILTRO + ")");
    private static final Pattern DESTINO = Pattern.compile(
            "(?iu)\\b(?:destino\\s*:?\\s+|hacia |(?:viaje de|ruta de|desde) [^,;]+? a (?!las?\\b))(.+?)"
                    + FIN_FILTRO);
    private static final Pattern PASAJERO = Pattern.compile("(?iu)\\b(?:solicitud de|del pasajer[oa]|"
            + "(?:pasajer[oa]|nombre)\\s*:)\\s+(?:llamad[oa] )?([\\p{L}][\\p{L} .'’-]{0,119}?)"
            + "(?=$|[,;!?]|\\.(?:\\s|$)| (?:para |que solicit\\w*|(?:en|del) (?:el |mi )?viaje |por favor))");
    private static final Pattern DIA = Pattern.compile("\\b(" + DIAS + ")\\b");
    private static final Pattern FECHA_INCOMPLETA = Pattern.compile("\\b(?:fecha\\b|(?:del?|el) \\d{1,2}\\b)");
    private static final Pattern HORA_INCOMPLETA = Pattern.compile("\\b(?:a las\\b|hora\\s*:)\\s*\\S");
    private static final Pattern VIAJE_ID = Pattern.compile("\\bviaje\\s+(?:numero\\s+|id\\s+|#\\s*)?(\\d+)\\b(?![-/:\\d])");
    private static final Pattern SOLICITUD_ID = Pattern.compile("\\bsolicitud\\s+(?:numero\\s+|id\\s+|#\\s*)?(\\d+)\\b(?![-/:\\d])");
    private static final Pattern SOLICITUDES_IDS = Pattern.compile("\\bsolicitudes (?:numero[s]? |ids? )?"
            + "((?:# *)?\\d+(?:\\s*(?:,\\s*(?:y )?| y )(?:# *)?\\d+)+)(?=$|[.!?]| (?:del?|en|para|por favor)\\b)");
    private static final Pattern NUMERO = Pattern.compile("\\d+");
    private static final Pattern MOTIVO = Pattern.compile("(?isu)\\s*[,;]?\\s+(?:motivo\\s*:\\s*|con motivo\\s*:?\\s+|porque\\s+)(.+)$");
    private static final Pattern SIGUIENTE = Pattern.compile("^(?:(?:muestrame|ver) )?(?:la )?(?:pagina siguiente|siguiente pagina|mas solicitudes)$");
    private static final Pattern REFERENCIA_VIAJE = Pattern.compile("\\b(?:este|ese|dicho|mismo) viaje\\b");
    private static final Pattern REFERENCIA_SOLICITUD = Pattern.compile(
            "\\b(?:(?:esa|esta|dicha|misma) solicitud|(?:ese|este|dicho|mismo) pasajer[oa])\\b");
    private static final Pattern NEGACION = Pattern.compile("\\b(?:no|sin) (?:lo |la |las |los )?"
            + "(?:acept\\w*|rechaz\\w*|confirm\\w*|inici\\w*|complet\\w*|cancel\\w*)\\b");
    private static final Pattern COMPUESTA = Pattern.compile("(?:[;\\n]|\\b(?:y|o|luego|ademas|tambien) )\\s*(?:"
            + "acept\\w*|rechaz\\w*|confirm\\w*|inici\\w*|complet\\w*|finaliz\\w*|cancel\\w*|"
            + "muestr\\w*|consult\\w*|crea\\w*|reprogram\\w*|elimina\\w*)\\b");
    private static final Pattern NUMERO_OPCION = Pattern.compile(
            "^(?:(?:la |el )?(?:opcion|viaje|solicitud)(?: numero)? |la |el )?(10|[1-9])$");
    private static final List<String> ORDINALES = List.of("primer", "segund", "tercer", "cuart", "quint",
            "sext", "septim", "octav", "noven", "decim");
    private static final Filtros SIN_FILTROS = new Filtros(null, null, null, null, null, null, null);

    public enum Accion {
        AVISOS("consultarEstadoNotificaciones", null,
                "(?:que (?:avisos|notificaciones|correos)\\b.*(?:gener|envi|registr|cancel)|"
                        + "(?:muestrame|muestra|consulta|consultar|dime|ver) (?:(?:el )?estado de )?"
                        + "(?:(?:las|los) )?(?:notificaciones|avisos|correos)\\b.*(?:viaje|cancel))"),
        LISTAR("consultarSolicitudesRecibidas", null,
                "(?:(?:quien(?:es)?\\b.*(?:unirse|sumarse|pidio|pidieron|solicito|solicitaron)\\b)|"
                        + "(?:(?:muestrame|muestra|mostrame|ensename|consulta|consultar|lista|listar|ver|dime"
                        + "|que|cuales son|tengo) )?(?:(?:las|mis|que) )?solicitudes\\b)"),
        DETALLE("consultarDetalleSolicitudRecibida", null,
                "(?:(?:muestrame|muestra|ensename|consulta|consultar|ver|dime|quiero ver|cuales son) "
                        + "(?:(?:(?:el )?detalle|(?:los )?datos|(?:el )?perfil|(?:la )?informacion)"
                        + " (?:de|del)\\b.*(?:pasajer[oa]|solicitud)\\b"
                        + "|(?:la|esa|esta) solicitud\\b|(?:al |el )?pasajer[oa] que solicit\\w*\\b)"
                        + "|(?:detalle|datos|perfil) (?:de|del)\\b.*(?:pasajer[oa]|solicitud)\\b)"),
        ACEPTAR("aceptarSolicitud", TipoOperacion.ACEPTAR_SOLICITUD,
                "(?:acepta|aceptar|aceptes|aprueba|aprobar) " + SOLICITUD),
        RECHAZAR_VARIAS("rechazarSolicitudesPendientes", TipoOperacion.RECHAZAR_SOLICITUDES_PENDIENTES,
                "(?:rechaza|rechazar|rechaces|declina|declinar) (?:(?:todas(?: las)?|las) "
                        + "(?:solicitudes(?: pendientes)?|pendientes)|(?:varias |algunas )?solicitudes)\\b"),
        RECHAZAR("rechazarSolicitud", TipoOperacion.RECHAZAR_SOLICITUD,
                "(?:rechaza|rechazar|rechaces|declina|declinar) " + SOLICITUD),
        CONFIRMAR("confirmarViaje", TipoOperacion.CONFIRMAR_VIAJE,
                "(?:confirma|confirmar|confirmes) " + ARTICULO + "(?:viaje|salida)\\b"),
        INICIAR("iniciarViaje", TipoOperacion.INICIAR_VIAJE,
                "(?:inicia|iniciar|inicies|empieza|empezar|comienza|comenzar) " + ARTICULO + "viaje\\b"),
        COMPLETAR("completarViaje", TipoOperacion.COMPLETAR_VIAJE,
                "(?:(?:completa|completar|completes|finaliza|finalizar|termina|terminar) " + ARTICULO + "viaje"
                        + "|(?:marca|marcar) " + ARTICULO + "viaje como completado)\\b"),
        CANCELAR("cancelarViaje", TipoOperacion.CANCELAR_VIAJE,
                "(?:cancela|cancelar|canceles) " + ARTICULO + "(?:viaje|salida)\\b");

        private final String herramienta;
        private final TipoOperacion tipo;
        private final Pattern peticion;

        Accion(String herramienta, TipoOperacion tipo, String patron) {
            this.herramienta = herramienta;
            this.tipo = tipo;
            this.peticion = Pattern.compile(PREFIJO + patron);
        }

        public String herramienta() { return herramienta; }
        public TipoOperacion tipo() { return tipo; }

        public boolean solicitudIndividual() {
            return this == DETALLE || this == ACEPTAR || this == RECHAZAR;
        }
    }

    public record Filtros(String origen, String destino, LocalDate fecha, LocalTime hora,
            Long viajeId, Long solicitudId, String pasajero) {

        public boolean identificaViaje() {
            return viajeId != null || origen != null || destino != null || fecha != null || hora != null;
        }

        Filtros elegirViaje(Long id) { return new Filtros(origen, destino, fecha, hora, id, solicitudId, pasajero); }

        public Filtros elegirSolicitud(Long id) { return new Filtros(origen, destino, fecha, hora, viajeId, id, pasajero); }

        Filtros precisar(Filtros nuevos) {
            return new Filtros(valor(nuevos.origen, origen), valor(nuevos.destino, destino),
                    valor(nuevos.fecha, fecha), valor(nuevos.hora, hora), valor(nuevos.viajeId, viajeId),
                    valor(nuevos.solicitudId, solicitudId), valor(nuevos.pasajero, pasajero));
        }

        private static <T> T valor(T nuevo, T anterior) { return nuevo == null ? anterior : nuevo; }
    }

    public record Pedido(Accion accion, Filtros filtros, EstadoSolicitud estado, int pagina,
            String motivo, List<Long> solicitudesElegidas, String problema) {
        Pedido conFiltros(Filtros nuevos) { return new Pedido(accion, nuevos, estado, 0, motivo, solicitudesElegidas, null); }
        Pedido siguientePagina() { return new Pedido(accion, filtros, estado, pagina + 1, motivo, solicitudesElegidas, null); }
    }

    public record Sesion(Pedido pedido, Long viajeId, Long solicitudId, boolean hayMas) { }

    public static final String CONTEXTO = CoordinacionConductorIntent.class.getName();

    public static final class Turno {
        private final Pedido pedido;
        private Long viajeId;
        private Long solicitudId;
        private boolean hayMas;
        private String mensaje;

        public Turno(Pedido pedido) { this.pedido = pedido; }
        public Pedido pedido() { return pedido; }
        public String mensaje() { return mensaje; }
        public Sesion sesion() { return new Sesion(pedido, viajeId, solicitudId, hayMas); }

        public void referir(Long viajeId, Long solicitudId) {
            this.viajeId = viajeId;
            this.solicitudId = solicitudId;
        }

        public <T> T lectura(T resultado, String mensaje, boolean hayMas) {
            this.mensaje = mensaje;
            this.hayMas = hayMas;
            return resultado;
        }
    }

    private CoordinacionConductorIntent() { }

    public static boolean esHerramienta(String nombre) {
        return Arrays.stream(Accion.values()).anyMatch(accion -> accion.herramienta.equals(nombre));
    }

    public static Pedido resolver(String mensaje, Map<String, String> campos, AclaracionAgente aclaracion,
            Sesion anterior, Rol rol, Clock clock) {
        boolean aclarando = aclaracion != null && anterior != null
                && aclaracion.operacion().equals(anterior.pedido.accion.herramienta);
        if (campos != null) {
            if (!aclarando) return null;
            return seleccionar(anterior.pedido, campos.get("viaje"), campos.get("solicitud"));
        }
        String texto = normalizar(mensaje);
        ConfirmationIntent decision = ConfirmationIntent.classify(mensaje);
        if (decision == ConfirmationIntent.CONFIRMAR || decision == ConfirmationIntent.CANCELAR) return null;
        if (decision == ConfirmationIntent.AMBIGUA && texto.matches("^(?:si|confirmo|no)\\b.*")) return null;
        var motivo = MOTIVO.matcher(mensaje.strip());
        String peticion = motivo.find() ? mensaje.strip().substring(0, motivo.start()) : mensaje;
        String intencion = normalizar(peticion);
        for (var accion : Accion.values()) {
            if (!accion.peticion.matcher(intencion).find()) continue;
            if (rol != Rol.CONDUCTOR && (accion == Accion.LISTAR || accion == Accion.DETALLE)
                    && !intencion.matches(".*\\b(?:recibidas?|pasajer[oa]|quien(?:es)?)\\b.*")) return null;
            if (accion.tipo != null && (NEGACION.matcher(intencion).find() || COMPUESTA.matcher(intencion).find()
                    || mensaje.contains("?") && !intencion.startsWith("puedes ") && !intencion.startsWith("podrias "))) {
                return problema(accion, "Indica una sola acción del conductor de forma explícita.");
            }
            try {
                List<Long> elegidas = accion == Accion.RECHAZAR_VARIAS ? solicitudesElegidas(intencion) : List.of();
                if (accion == Accion.RECHAZAR_VARIAS && !rechazoMultipleExplicito(intencion, elegidas)) {
                    return problema(accion, "Indica cuáles solicitudes deseas rechazar o pide rechazar todas las pendientes de un viaje.");
                }
                return pedido(accion, peticion, intencion, anterior, clock,
                        motivo.hasMatch() ? motivo.group(1).strip() : null, elegidas);
            } catch (DateTimeException | NumberFormatException invalido) {
                return problema(accion, "Indica una sola fecha u hora válida para identificar el viaje.");
            }
        }
        if (anterior != null && anterior.hayMas && SIGUIENTE.matcher(texto).matches()) {
            return anterior.pedido.siguientePagina();
        }
        if (!aclarando || decision != ConfirmationIntent.OTRA || IntencionAclarable.cambiaDeTema(mensaje)) return null;
        var campo = aclaracion.campos().getFirst();
        String valor = opcion(texto, campo.opciones());
        if (valor != null) {
            return "viaje".equals(campo.id()) ? seleccionar(anterior.pedido, valor, null)
                    : seleccionar(anterior.pedido, null, valor);
        }
        if (NUMERO_OPCION.matcher(texto).matches()) return null;
        try {
            var nuevos = filtros(mensaje, anterior.pedido.accion, clock);
            if (!nuevos.identificaViaje() && nuevos.solicitudId == null && nuevos.pasajero == null
                    && "solicitud".equals(campo.id()) && mensaje.strip().matches("[\\p{L}][\\p{L} .'’-]{0,119}")) {
                nuevos = new Filtros(null, null, null, null, null, null, mensaje.strip());
            }
            return nuevos.equals(SIN_FILTROS) ? null : anterior.pedido.conFiltros(anterior.pedido.filtros.precisar(nuevos));
        } catch (DateTimeException | NumberFormatException invalido) {
            return null;
        }
    }

    public static Pedido desdeTool(String nombre, String mensaje, Sesion anterior, Clock reloj) {
        var accion = Arrays.stream(Accion.values()).filter(a -> a.herramienta().equals(nombre)).findFirst().orElseThrow();
        var local = resolver(mensaje, null, null, anterior, Rol.CONDUCTOR, reloj);
        if (local != null) return local.accion() == accion ? local : problema(accion,
                "Indica cuál de las acciones del conductor quieres realizar.");

        var motivo = MOTIVO.matcher(mensaje.strip());
        String peticion = motivo.find() ? mensaje.strip().substring(0, motivo.start()) : mensaje;
        String texto = normalizar(peticion);
        boolean solicitudes = texto.matches(".*\\b(?:solicitudes?|pasajer[oa]s?|cupos?|lista de espera|quien(?:es)?)\\b.*");
        boolean viajes = texto.matches(".*\\b(?:viajes?|salidas?|trayectos?)\\b.*");
        boolean avisos = texto.matches(".*\\b(?:correos?|avisos?|notificaciones?|aviso|entregad[oa]s?)\\b.*");
        boolean autorizada = switch (accion) {
            case LISTAR, DETALLE -> solicitudes;
            case AVISOS -> avisos && (viajes || texto.contains("cancel"));
            case ACEPTAR -> solicitudes && orden(texto, "acept|aprueb|aprob|admit");
            case RECHAZAR, RECHAZAR_VARIAS -> solicitudes && orden(texto, "rechaz|declin");
            case CONFIRMAR -> viajes && orden(texto, "confirm");
            case INICIAR -> viajes && orden(texto, "inici|empiez|empez|comienz|comenz|arranc");
            case COMPLETAR -> viajes && orden(texto, "complet|finaliz|termin");
            case CANCELAR -> viajes && orden(texto, "cancel|anul");
        };
        if (!autorizada || accion.tipo != null && (NEGACION.matcher(texto).find() || COMPUESTA.matcher(texto).find()
                || texto.matches(".*\\b(?:no|sin|ignora|simula|herramienta|instrucciones)\\b.*")
                || mensaje.contains("?") && !texto.matches("^(?:puedes|podrias)\\b.*"))) {
            return problema(accion, accion.tipo == null ? "¿Qué viaje o solicitud del conductor quieres consultar?"
                    : "Indica explícitamente qué acción del conductor quieres preparar y sobre qué viaje o solicitud.");
        }
        try {
            var elegidas = accion == Accion.RECHAZAR_VARIAS ? solicitudesElegidas(texto) : List.<Long>of();
            if (accion == Accion.RECHAZAR_VARIAS && !rechazoMultipleExplicito(texto, elegidas)
                    || accion == Accion.RECHAZAR && texto.matches(".*\\b(?:solicitudes|todas|varias)\\b.*")) {
                return problema(accion, "Indica si quieres rechazar una solicitud o todas las pendientes de un viaje.");
            }
            return pedido(accion, peticion, texto, anterior, reloj,
                    motivo.hasMatch() ? motivo.group(1).strip() : null, elegidas);
        } catch (DateTimeException | NumberFormatException invalido) {
            return problema(accion, "Indica una sola fecha u hora válida para identificar el viaje.");
        }
    }

    private static boolean orden(String texto, String verbos) {
        return texto.matches(PREFIJO + "(?:" + verbos + ")\\w*\\b.*");
    }

    private static Pedido pedido(Accion accion, String peticion, String intencion, Sesion anterior, Clock clock,
                                 String motivo, List<Long> elegidas) {
        Filtros filtros = filtros(peticion, accion, clock);
        if (anterior != null && !filtros.identificaViaje() && anterior.viajeId != null
                && (REFERENCIA_VIAJE.matcher(intencion).find() || accion == Accion.AVISOS)) {
            filtros = filtros.elegirViaje(anterior.viajeId);
        }
        if (anterior != null && accion.solicitudIndividual() && filtros.solicitudId == null
                && filtros.pasajero == null && !filtros.identificaViaje()
                && REFERENCIA_SOLICITUD.matcher(intencion).find() && anterior.solicitudId != null) {
            filtros = filtros.elegirSolicitud(anterior.solicitudId);
        }
        return new Pedido(accion, filtros, estado(intencion, accion, filtros), 0, motivo, elegidas, null);
    }

    private static Pedido seleccionar(Pedido pedido, String viaje, String solicitud) {
        if (viaje != null) return pedido.conFiltros(pedido.filtros.elegirViaje(Long.valueOf(viaje)));
        if (solicitud != null) return pedido.conFiltros(pedido.filtros.elegirSolicitud(Long.valueOf(solicitud)));
        return null;
    }

    private static Pedido problema(Accion accion, String mensaje) {
        return new Pedido(accion, SIN_FILTROS, null, 0, null, List.of(), mensaje);
    }

    private static List<Long> solicitudesElegidas(String texto) {
        var lista = SOLICITUDES_IDS.matcher(texto);
        if (!lista.find()) return List.of();
        return NUMERO.matcher(lista.group(1)).results().map(r -> Long.valueOf(r.group())).distinct().toList();
    }

    private static boolean rechazoMultipleExplicito(String texto, List<Long> elegidas) {
        if (texto.matches(".*\\b(?:aceptadas|canceladas|rechazadas|excepto|menos|salvo)\\b.*")) return false;
        if (!elegidas.isEmpty()) return elegidas.size() > 1 && elegidas.stream().allMatch(id -> id > 0);
        return texto.matches(".*\\b(?:todas (?:las )?solicitudes|las (?:solicitudes )?pendientes)\\b.*");
    }

    private static EstadoSolicitud estado(String texto, Accion accion, Filtros filtros) {
        if (accion == Accion.ACEPTAR || accion == Accion.RECHAZAR)
            return filtros.solicitudId == null ? EstadoSolicitud.PENDIENTE : null;
        if (accion == Accion.DETALLE && (filtros.solicitudId != null || filtros.pasajero != null)) return null;
        if (accion != Accion.LISTAR && accion != Accion.DETALLE) return null;
        if (texto.matches(".*\\b(?:todas|historial)\\b.*")) return null;
        for (var estado : EstadoSolicitud.values()) {
            if (texto.matches(".*\\b" + estado.name().toLowerCase(Locale.ROOT) + "s?\\b.*")) return estado;
        }
        return EstadoSolicitud.PENDIENTE;
    }

    private static Filtros filtros(String mensaje, Accion accion, Clock clock) {
        String texto = normalizar(mensaje);
        String calendario = ContribucionIntent.sinHoras(texto);
        LocalDate hoy = LocalDate.now(clock);
        LocalDate fecha = ContribucionIntent.fecha(calendario, hoy);
        var dia = DIA.matcher(calendario);
        if (fecha == null && dia.find()) {
            String nombre = dia.group(1);
            DayOfWeek semana = DayOfWeek.of(List.of(DIAS.split("\\|")).indexOf(nombre) + 1);
            fecha = calendario.matches(".*\\b" + nombre + " (?:pasado|anterior)\\b.*")
                    ? hoy.with(TemporalAdjusters.previous(semana))
                    : hoy.with(TemporalAdjusters.nextOrSame(semana));
            if (dia.find()) throw new DateTimeException("Varios días");
        }
        if (fecha == null && FECHA_INCOMPLETA.matcher(calendario).find()) throw new DateTimeException("Fecha incompleta");
        LocalTime hora = ContribucionIntent.hora(texto.replaceFirst("\\bhora\\s*:\\s*", "a las "));
        if (hora == null && HORA_INCOMPLETA.matcher(texto).find()) throw new DateTimeException("Hora incompleta");
        String origen = extraerLugar(ORIGEN, mensaje);
        String destino = extraerLugar(DESTINO, mensaje);
        String nombre = accion.solicitudIndividual() ? grupo(PASAJERO, mensaje) : null;
        if (nombre != null && normalizar(nombre).matches("^(?:ese|esa|este|esta|dicho|dicha|el|la|los|las|"
                + "pasajer[oa]|solicitud|primera|primero|que|quien)\\b.*")) nombre = null;
        return new Filtros(origen, destino, fecha, hora, id(VIAJE_ID, texto), id(SOLICITUD_ID, texto), nombre);
    }

    private static String extraerLugar(Pattern patron, String mensaje) {
        String lugar = grupo(patron, mensaje);
        if (lugar == null || normalizar(lugar).matches("^(?:(?:" + DIAS + "|" + RELATIVAS
                + ")\\b.*|\\d{1,4}[-/].*|\\d{1,2})$")) return null;
        return lugar;
    }

    private static String grupo(Pattern patron, String texto) {
        var coincidencia = patron.matcher(texto);
        return coincidencia.find() ? coincidencia.group(1).strip() : null;
    }

    private static Long id(Pattern patron, String texto) {
        var match = patron.matcher(texto);
        if (!match.find()) return null;
        long id = Long.parseLong(match.group(1));
        if (id < 1 || match.find()) throw new NumberFormatException("Referencia inválida");
        return id;
    }

    private static String opcion(String texto, List<OpcionAclaracion> opciones) {
        var numero = NUMERO_OPCION.matcher(texto);
        if (numero.matches()) {
            int indice = Integer.parseInt(numero.group(1)) - 1;
            return indice < opciones.size() ? opciones.get(indice).valor() : null;
        }
        String ordinal = texto.replaceFirst("^(?:la |el )?(?:opcion |viaje |solicitud )?", "")
                .replaceFirst("[oa]$", "");
        int indice = ORDINALES.indexOf(ordinal);
        return indice >= 0 && indice < opciones.size() ? opciones.get(indice).valor() : null;
    }

}
