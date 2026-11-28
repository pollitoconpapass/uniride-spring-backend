package com.uniride.agent.conversation;

import static com.uniride.agent.conversation.Aclaraciones.Campo.*;
import static com.uniride.agent.support.AgentText.normalizar;

import com.uniride.agent.conversation.Aclaraciones.Campo;
import com.uniride.agent.dto.AclaracionAgente;
import com.uniride.agent.dto.ViajeResumen;
import com.uniride.agent.dto.OpcionAclaracion;
import com.uniride.agent.dto.OpcionAclaracion;
import com.uniride.enums.Rol;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Bounded input for route and trip callbacks. A continuation can change only prompted fields. */
public final class IntencionAclarable {
    public static final String CONTEXTO_CUPO = "seleccionCupo";
    private static final String INICIO = "^(?:por favor,? )?(?:ahora )?(?:(?:quiero|quisiera|necesito|puedes|podrias|ayudame a) )?";
    private static final Pattern OTRA_CREACION = Pattern.compile(INICIO + "cre(?:a|ar) (?:otr[oa]|un[oa] mas)$");
    private static final Pattern CAMBIAR_HORA = Pattern.compile(INICIO + "(?:cambia|cambiar|modifica|modificar) la hora$");
    private static final Pattern MISMO_ORIGEN = Pattern.compile(INICIO + "(?:usa|usar|conserva|conservar) el mismo origen$");
    private static final Pattern VARIAS_ACCIONES = Pattern.compile("[.!?]\\s+\\S|\\b(?:y|e|o|luego|ademas|tambien) "
            + "(?:crea|publica|programa|reprograma|registra|modifica|edita|activa|desactiva|solicita|"
            + "muestra|busca|consulta|cancela|elimina|mira)\\w*\\b");
    private static final Pattern NUEVO_TEMA = Pattern.compile(INICIO + "(?:crea\\w*|publica\\w*|"
            + "elimina\\w*|registra\\w*|reprograma\\w*|edita\\w*|cambia\\w*|activa\\w*|desactiva\\w*|"
            + "cancela\\w*|agrega\\w*|anade\\w*|quita\\w*|muestra\\w*|busca\\w*|dime|consulta\\w*|descarga\\w*|exporta\\w*|"
            + "quiero|olvida|hola|gracias|ayudame con|ensena\\w*|(?:mis|mi) (?:rutas?|viajes?|solicitudes?|metodos?|"
            + "estadisticas|historial))\\b");
    private static final Pattern ETIQUETA = Pattern.compile("(?iu)(?:^|(?:[;,\\r\\n]|\\b(?:con|y|e)\\b)\\s*)"
            + "(origen actual|destino actual|nuevo origen|puntos? de referencia|referencias|capacidad(?: m[aá]xima)?|"
            + "zonas sin parada|nota de contribuci[oó]n|nueva fecha|nueva hora|tipo de compensaci[oó]n|preferencia|"
            + "d[ií]a(?: de la semana)?|origen|destino|fecha|hora)\\s*(?::\\s*|\\s+)(?:de\\s+(?=[+-]?\\d))?");
    private static final Pattern ORIGEN_EXPLICITO = Pattern.compile("(?iu)\\b(?:desde|(?:ruta|viaje|salida|solicitud|cupo|reserva) de) "
            + "([\\p{L}\\p{N}][\\p{L}\\p{N} .’'()-]{0,254}?)(?=$|[.,;!?]| (?:a |hacia |con |por |del? \\d|el \\d))");
    private static final Pattern DESTINO_VIAJE = Pattern.compile("(?iu)\\b(?:desde|(?:ruta|viaje|salida) de) [^,;!?]+? "
            + "(?:a (?!las? )|hacia )(?:la |el )?([\\p{L}\\p{N}][\\p{L}\\p{N} .’'()-]{0,254}?)"
            + "(?=$|[.,;!?]| (?:a las? |con |del? \\d|el \\d|para |por favor))");
    private static final Pattern RUTA_POR_TEXTO = Pattern.compile("(?iu)^ruta(?: registrada)?\\s*:\\s*(.+)$");
    private static final Pattern RUTA_NUMERADA = Pattern.compile("^ruta(?: numero)? ([1-9]\\d{0,5}|[a-z]+)$");
    private static final List<String> NUMEROS_RUTA = List.of("uno", "dos", "tres", "cuatro", "cinco", "seis", "siete",
            "ocho", "nueve", "diez", "once", "doce", "trece", "catorce", "quince", "dieciseis", "diecisiete",
            "dieciocho", "diecinueve", "veinte");
    private static final Pattern SIGUIENTE_PAGINA = Pattern.compile("^(?:siguiente pagina|pagina siguiente|(?:ver )?mas rutas)$");
    private static final Pattern PAGINA_ANTERIOR = Pattern.compile("^(?:pagina anterior|anterior pagina)$");
    private static final Pattern NUEVO_HORARIO = Pattern.compile("\\bpara (?:el )?([^.!?]+)");
    private static final Pattern ENTERO = Pattern.compile("(?iu)^([+-]?\\d+|un[oa]?|dos|tres|cuatro|cinco|seis)"
            + "(?: pasajeros?)?$");
    private static final Pattern PETICION = Pattern.compile("^(?:por favor,? )?(?:ahora )?(?:"
            + "me (?:puedes |podrias )?ayuda(?:s|r) a |me gustaria |(?:quiero|quisiera|necesito) (?:que me )?"
            + "|(?:puedes|podrias) |ayudame a )?");
    private static final Pattern PETICION_EN_PREGUNTA = Pattern.compile("^(?:por favor,? )?"
            + "(?:me (?:ayudas|(?:puedes|podrias) ayudar)|puedes|podrias|ayudame)\\b");
    private static final String ANTES_DEL_RECURSO = "(?:(?:a|al|los datos de|la informacion de|el horario de|la hora de) )?"
            + "(?:(?:un|una|el|la|las|los|mi|mis|otro|otra|nuevo|nueva|todas|todos) ){0,3}";
    private static final Pattern TEXTO_AISLADO = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} .'’()/,-]*");
    private static final Pattern RESPUESTA_CONVERSACIONAL = Pattern.compile("^(?:no|si|pero|aunque|tal vez|quizas|"
            + "no se|no recuerdo|buenos dias|buenas(?: tardes| noches)?|saludos|ok|vale|perfecto|entendido|listo|"
            + "como|cual(?:es)?|cuando|donde|que (?:es|son|significa)|por que|puedes|podrias|explica\\w*|"
            + "ayudame|necesito|quisiera|me gustaria|por favor|cuentame|muestrame|ensename|lista\\w*|"
            + "cuanto|tengo|hay|tienes|quisiera saber|ignora\\w*|ejecuta\\w*|me (?:crea|registra|muestra|cambia|edita)\\w*)\\b");
    private static final Pattern RECHAZO = Pattern.compile("\\b(?:no (?:(?:la|lo|las|los|me) )?(?:quiero|quisiera|necesito|cre\\w*|publi\\w*|"
            + "elimin\\w*|cambi\\w*|edit\\w*|reprogram\\w*|cancel\\w*|solicit\\w*|program\\w*|lo hagas)|"
            + "sin (?:crear|publicar|eliminar|preparar|editar|reprogramar|cancelar)|"
            + "(?:solo|solamente) (?:consult\\w*|pregunt\\w*|quiero saber))\\b");
    private static final Pattern CANCELAR_TODAS = Pattern.compile("^(?:cancel\\w*) (?:todas|todos) "
            + "(?:(?:las|mis|los) )?(?:solicitudes|cupos|reservas)\\b");
    private static final Pattern PEDIDO_CUPO = Pattern.compile(
            "^(?:inscrib\\w*|inscrit\\w*|registr(?:a(?:r)?me)|apunt\\w*|anot\\w*|particip\\w*|"
                    + "sumar\\w*|unir\\w*|ir|viajar|reserv\\w*|solicit\\w*|pedir|pide\\w*)\\b");
    private static final Pattern REFERENCIA_CUPO = Pattern.compile(
            "\\b(?:viajes?|salidas?|cupo|asiento|sitio|conductor|(?:al|en el|para el) de|ir con)\\b");

    private IntencionAclarable() { }

    public static boolean bloqueaPreparacion(String mensaje) {
        String texto = normalizar(mensaje);
        return RECHAZO.matcher(texto).find() || texto.matches(
                "^(?:por favor,? )?(?:no|nunca|jamas|como|que pasa si|explicame|puedo|podria|debo|es posible|quiero saber)\\b.*");
    }

    public static boolean esPeticionDeCupo(String mensaje) {
        String texto = PETICION.matcher(normalizar(mensaje)).replaceFirst("");
        return PEDIDO_CUPO.matcher(texto).find() && REFERENCIA_CUPO.matcher(texto).find()
                && !bloqueaPreparacion(mensaje);
    }

    public enum Accion {
        CREAR_RUTA("crearRuta", Rol.CONDUCTOR, "(?:crea|crear|publica|publicar) (?:una |otra |mi |nueva )?ruta(?: mas)?",
                "cre(?:a|ar|es)|public(?:a|ar|es)|regist(?:ra|rar|res)", "ruta",
                ORIGEN, REFERENCIAS, CAPACIDAD, ZONAS_SIN_PARADA, NOTA_CONTRIBUCION),
        EDITAR_RUTA("editarRuta", Rol.CONDUCTOR, "(?:edita|editar|actualiza|actualizar|modifica|modificar) (?:una |la |mi )?ruta",
                "edit(?:a|ar|es)|actualiz(?:a|ar|es)|modifi(?:ca|car|ques)", "ruta",
                ORIGEN_ACTUAL, DESTINO_ACTUAL, NUEVO_ORIGEN, REFERENCIAS, CAPACIDAD, ZONAS_SIN_PARADA, NOTA_CONTRIBUCION),
        ELIMINAR_RUTA("eliminarRuta", Rol.CONDUCTOR, "(?:elimina|eliminar|borra|borrar) (?:una |la |mi )?ruta",
                "elimin(?:a|ar|es)|borr(?:a|ar|es)", "ruta", ORIGEN_ACTUAL, DESTINO_ACTUAL),
        CREAR_VIAJE("crearViaje", Rol.CONDUCTOR, "(?:crea|crear|publica|publicar|programa|programar) (?:(?:un |otro |mi |nuevo )?viaje(?: mas)?|(?:una |otra |mi )?salida)",
                "cre(?:a|ar|es)|public(?:a|ar|es)|program(?:a|ar|es)", "viaje|salida", ORIGEN, DESTINO, FECHA, HORA),
        REPROGRAMAR("reprogramarViaje", Rol.CONDUCTOR, "(?:reprograma|reprogramar|cambia el horario de|cambiar el horario de) (?:(?:un |el |mi )?viaje|(?:una |la |mi )?salida)",
                "reprogram(?:a|ar|es)|cambi(?:a|ar|es)", "viaje|salida", ORIGEN, DESTINO, DIA, FECHA, HORA, NUEVA_FECHA, NUEVA_HORA),
        UNIRSE("solicitarUnirse", Rol.PASAJERO, "(?:solicita|solicitar|reserva|reservar|pide|pedir) (?:un )?cupo|un cupo|(?:unirme|uneme|sumarme) (?:a |al )?(?:un |el )?viaje",
                "solicit(?:a|ar|ame|es)|reserv(?:a|ar|ame|es)|pide(?:me)?|pedir|pidas|unirme|uneme|sumarme",
                "cupo|asiento|viaje", ORIGEN, DESTINO, DIA, FECHA, HORA, COMPENSACION),
        CANCELAR("cancelarSolicitud", Rol.PASAJERO, "(?:cancela|cancelar) (?:una |la |mi )?solicitud(?: pendiente)?",
                "cancel(?:a|ar|es)", "solicitud(?:es)?|cupos?|reservas?", ORIGEN, DESTINO, DIA, FECHA, HORA);

        final String herramienta;
        final Rol rol;
        final Pattern intencion;
        final Pattern peticion;
        final List<Campo> campos;

        Accion(String herramienta, Rol rol, String patron, String verbos, String recursos, Campo... campos) {
            this.herramienta = herramienta;
            this.rol = rol;
            this.intencion = Pattern.compile(INICIO + "(?:" + patron + ")(?:$| )");
            this.peticion = Pattern.compile("^(?:" + verbos + ") " + ANTES_DEL_RECURSO + "(?:" + recursos + ")\\b");
            this.campos = List.of(campos);
        }

        private String argumento(Campo campo) {
            return switch (campo) {
                case ORIGEN -> this == CREAR_VIAJE ? "origenRuta" : "origen";
                case DESTINO -> this == CREAR_VIAJE ? "destinoRuta" : "destino";
                case ORIGEN_ACTUAL -> "origenRuta";
                case DESTINO_ACTUAL -> "destinoRuta";
                case REFERENCIAS -> "puntosReferencia";
                case CAPACIDAD -> "capacidadMaxima";
                case COMPENSACION -> "preferencia";
                default -> campo.id();
            };
        }

        public String herramienta() { return herramienta; }
        public Rol rol() { return rol; }
    }

    public record Contexto(Accion accion, String origen, String destino, String fecha, String hora) { }

    public record Borrador(Accion accion, Map<Campo, String> datos, boolean todas, SeleccionRutaViaje.Estado rutaViaje,
            Map<String, ViajeResumen> opcionesViaje, ViajeResumen viajeElegido)
            implements AgentConversationStore.Draft {
        Borrador(Accion accion, Map<Campo, String> datos) { this(accion, datos, false); }
        Borrador(Accion accion, Map<Campo, String> datos, boolean todas) {
            this(accion, datos, todas, accion == Accion.CREAR_VIAJE ? SeleccionRutaViaje.INICIAL : null);
        }

        private Borrador(Accion accion, Map<Campo, String> datos, boolean todas, SeleccionRutaViaje.Estado rutaViaje) {
            this(accion, datos, todas, rutaViaje, Map.of(), null);
        }

        public Borrador {
            datos = Map.copyOf(datos);
            opcionesViaje = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(opcionesViaje));
        }

        public Map<String, Object> argumentos() {
            var argumentos = new LinkedHashMap<String, Object>();
            datos.forEach((campo, valor) -> argumentos.put(accion.argumento(campo),
                    campo == CAPACIDAD ? Integer.valueOf(valor) : valor));
            if (todas) argumentos.put("todas", true);
            return argumentos;
        }

        public Borrador continuar(Map<String, String> campos) {
            var valores = new EnumMap<Campo, String>(Campo.class);
            valores.putAll(datos);
            accion.campos.forEach(campo -> {
                if (campos.containsKey(campo.id())) valores.put(campo, campos.get(campo.id()));
            });
            var estado = rutaViaje;
            if (estado != null && campos.containsKey(RUTA.id())) estado = estado.elegir(campos.get(RUTA.id()));
            var seleccionado = opcionesViaje.getOrDefault(campos.getOrDefault(VIAJE.id(), ""), viajeElegido);
            return new Borrador(accion, valores, todas, estado, seleccionado == null ? opcionesViaje : Map.of(), seleccionado);
        }

        private Borrador vincularViaje(String mensaje, List<ViajeResumen> recientes) {
            if (accion != Accion.UNIRSE || recientes.isEmpty() || viajeElegido != null || !opcionesViaje.isEmpty()) return this;
            String texto = normalizar(mensaje);
            var posibles = recientes.stream().filter(v -> coincide(v, texto, datos)).toList();
            if (posibles.isEmpty()) return this;
            var opciones = new LinkedHashMap<String, ViajeResumen>();
            posibles.forEach(v -> opciones.put(java.util.UUID.randomUUID().toString(), v));
            var form = campoViaje(opciones);
            String ordinal = texto.replaceFirst("^.*?\\b(?:al|a la|en el|en la|para el|para la) ", "").strip();
            String elegido = Aclaraciones.EnvioAclaracion.valorSeleccionado(form, ordinal);
            boolean apunta = datos.containsKey(ORIGEN) || datos.containsKey(DESTINO) || datos.containsKey(FECHA)
                    || datos.containsKey(HORA)
                    || texto.matches(".*\\b(?:ese|este|esa|esta|dicho|dicha) (?:viaje|salida|conductor)\\b.*")
                    || posibles.size() < recientes.size()
                    || posibles.stream().anyMatch(v -> menciona(texto, v.origen()) || menciona(texto, v.conductorNombre()));
            if (elegido != null) return new Borrador(accion, datos, todas, null, Map.of(), opciones.get(elegido));
            if (apunta && posibles.size() == 1) return new Borrador(accion, datos, todas, null, Map.of(), posibles.getFirst());
            return new Borrador(accion, datos, todas, null, opciones, null);
        }

        public Borrador vincularLecturas(String mensaje, AgentConversationStore.Lecturas lecturas) {
            String texto = normalizar(mensaje);
            if (accion == Accion.CREAR_VIAJE && datos.get(ORIGEN) == null && datos.get(DESTINO) == null
                    && rutaViaje != null && rutaViaje.elegida() == null && rutaViaje.opciones().isEmpty()
                    && lecturas.rutas().size() == 1 && texto.matches(".*\\b(?:esa|esta|misma|dicha) ruta\\b.*")) {
                var ruta = lecturas.rutas().getFirst();
                return conRutaViaje(SeleccionRutaViaje.Estado.desdeConsulta(ruta));
            }
            if (accion == Accion.CANCELAR && datos.isEmpty() && !todas
                    && lecturas.solicitudes().size() == 1 && texto.matches(".*\\b(?:esa|esta|dicha) solicitud\\b.*")) {
                var viaje = lecturas.solicitudes().getFirst();
                var filtros = continuar(Map.of(ORIGEN.id(), viaje.origen(), DESTINO.id(), viaje.destino(),
                        FECHA.id(), viaje.fecha().toString(), HORA.id(), viaje.hora().toString()));
                return new Borrador(accion, filtros.datos(), false, null, Map.of(), viaje);
            }
            return vincularViaje(mensaje, lecturas.viajes());
        }

        public AclaracionAgente formularioViaje() {
            return opcionesViaje.isEmpty() ? null
                    : Aclaraciones.crear(accion.herramienta, campoViaje(opcionesViaje)).conOpcionesRapidas();
        }

        public Borrador conRutaViaje(SeleccionRutaViaje.Estado estado) {
            var valores = new EnumMap<Campo, String>(Campo.class);
            valores.putAll(datos);
            if (estado.limpiarFiltros()) {
                valores.remove(ORIGEN);
                valores.remove(DESTINO);
            }
            return new Borrador(accion, valores, todas, estado);
        }

        Borrador buscarRuta(String origen, String destino) {
            var valores = new EnumMap<Campo, String>(Campo.class);
            valores.putAll(datos);
            if (origen != null) {
                valores.put(ORIGEN, origen);
                valores.remove(DESTINO);
            }
            if (destino != null) valores.put(DESTINO, destino);
            return new Borrador(accion, valores, todas, SeleccionRutaViaje.INICIAL);
        }

        public Contexto contexto() {
            var ruta = rutaViaje == null ? null : rutaViaje.elegida();
            return new Contexto(accion, ruta == null ? datos.get(ORIGEN) : ruta.origen(),
                    ruta == null ? datos.get(DESTINO) : ruta.destino(),
                    datos.getOrDefault(NUEVA_FECHA, datos.get(FECHA)), datos.getOrDefault(NUEVA_HORA, datos.get(HORA)));
        }

        public AclaracionAgente completar(AclaracionAgente esquema) {
            var obligatorios = switch (accion) {
                case CREAR_RUTA -> List.of(ORIGEN, REFERENCIAS, CAPACIDAD);
                case CREAR_VIAJE -> List.of(FECHA, HORA);
                case REPROGRAMAR -> List.of(NUEVA_FECHA, NUEVA_HORA);
                case UNIRSE -> List.of(COMPENSACION);
                default -> List.<Campo>of();
            };
            var campos = new ArrayList<>(esquema.campos());
            for (var campo : obligatorios) {
                if (!datos.containsKey(campo) && campos.stream().noneMatch(f -> f.id().equals(campo.id()))) {
                    campos.add(campo.obligatorio());
                }
            }
            return campos.size() == esquema.campos().size() ? esquema : new AclaracionAgente(esquema.version(), null,
                    esquema.operacion(), esquema.contexto(), campos, esquema.alMenosUno(), null, "campo");
        }

        boolean faltanDatos() {
            return switch (accion) {
                case CREAR_RUTA -> !datos.keySet().containsAll(List.of(ORIGEN, REFERENCIAS, CAPACIDAD));
                case CREAR_VIAJE -> (!datos.containsKey(ORIGEN) && !datos.containsKey(DESTINO))
                        || !datos.keySet().containsAll(List.of(FECHA, HORA));
                case REPROGRAMAR -> !datos.keySet().containsAll(List.of(NUEVA_FECHA, NUEVA_HORA));
                case UNIRSE -> !datos.containsKey(COMPENSACION);
                case EDITAR_RUTA, ELIMINAR_RUTA -> !datos.containsKey(ORIGEN_ACTUAL) && !datos.containsKey(DESTINO_ACTUAL);
                case CANCELAR -> false;
            };
        }
    }

    private static boolean coincide(ViajeResumen viaje, String texto, Map<Campo, String> filtros) {
        if (!filtros.getOrDefault(FECHA, viaje.fecha().toString()).equals(viaje.fecha().toString())
                || filtros.containsKey(HORA) && !LocalTime.parse(filtros.get(HORA)).equals(viaje.hora())) return false;
        if (!normalizar(viaje.origen()).contains(normalizar(filtros.get(ORIGEN)))
                || !normalizar(viaje.destino()).contains(normalizar(filtros.get(DESTINO)))) return false;
        var origen = Pattern.compile("\\b(?:al de|en el de|viaje de|salida de) ([^,.!?]+?)(?= por | con |$)").matcher(texto);
        if (origen.find() && !normalizar(viaje.origen()).equals(origen.group(1).strip())) return false;
        var referencia = Pattern.compile("\\b(?:el de|por|con) ([\\p{L} .'-]+?)(?= a las | desde |$)").matcher(texto);
        if (referencia.find()) {
            String nombre = referencia.group(1).strip();
            if (!nombre.matches("(?:ese|este|el|la|favor|dinero|gasolina)\\b.*")
                    && !menciona(normalizar(viaje.conductorNombre()), nombre)
                    && !normalizar(viaje.origen()).equals(nombre)) return false;
        }
        return true;
    }

    private static boolean menciona(String texto, String valor) {
        String nombre = normalizar(valor);
        return !nombre.isBlank() && Pattern.compile("(?<![\\p{L}\\d])"
                + Pattern.quote(nombre) + "(?![\\p{L}\\d])").matcher(texto).find();
    }

    private static com.uniride.agent.dto.CampoAclaracion campoViaje(Map<String, ViajeResumen> opciones) {
        var etiquetas = new ArrayList<OpcionAclaracion>();
        opciones.forEach((id, v) -> {
            String dato = v.origen() + " → " + v.destino() + ", " + v.conductorNombre();
            String numero = "Viaje " + (etiquetas.size() + 1);
            String etiqueta = AclaracionAgente.textoSeguro(dato, 85) ? numero + " · " + dato : numero;
            etiquetas.add(new OpcionAclaracion(id, etiqueta + " · " + v.fecha() + " " + v.hora()));
        });
        return VIAJE.conOpciones(etiquetas);
    }

    public static Borrador inicioLocal(String mensaje, Contexto anterior, Clock clock) {
        Accion accion = detectar(mensaje, anterior);
        if (accion == null) return null;
        String texto = normalizar(mensaje);
        if (CAMBIAR_HORA.matcher(texto).matches() && anterior != null && anterior.origen() != null
                && anterior.fecha() != null && anterior.hora() != null
                && (anterior.accion() == Accion.CREAR_VIAJE || anterior.accion() == Accion.REPROGRAMAR)) {
            var datos = new EnumMap<Campo, String>(Campo.class);
            datos.put(ORIGEN, anterior.origen());
            if (anterior.destino() != null) datos.put(DESTINO, anterior.destino());
            datos.put(FECHA, anterior.fecha());
            datos.put(HORA, anterior.hora());
            datos.put(NUEVA_FECHA, anterior.fecha());
            return new Borrador(accion, datos);
        }
        var coincidencia = accion.intencion.matcher(texto);
        if (RECHAZO.matcher(texto).find() || VARIAS_ACCIONES.matcher(texto).find()) return null;
        if (!coincidencia.find()) return new Borrador(accion, Map.of());
        // The explicit bare request or labeled data can be resolved without asking the model.
        if (coincidencia.end() == texto.length()) return new Borrador(accion, Map.of());
        if (!etiquetado(mensaje) && !((accion == Accion.CREAR_RUTA || accion == Accion.CREAR_VIAJE)
                && ORIGEN_EXPLICITO.matcher(mensaje).find())) return null;
        return new Borrador(accion, extraer(mensaje, accion.campos, clock));
    }

    public static Borrador desdeTool(String herramienta, Map<?, ?> argumentos, String mensaje, Rol rol, Clock clock) {
        var clausula = clausulaAutorizada(herramienta, mensaje, rol);
        if (clausula == null) return null;
        var accion = clausula.accion();
        var datos = extraer(clausula.texto(), accion.campos, clock);
        String texto = normalizar(clausula.texto());
        for (var campo : accion.campos) {
            Object dato = argumentos.get(accion.argumento(campo));
            if (dato == null || datos.containsKey(campo)) continue;
            var valor = validar(campo, dato.toString(), clock);
            if (valor != null && figuraComoCampo(campo, valor, texto)) datos.put(campo, valor);
        }
        return new Borrador(accion, datos, accion == Accion.CANCELAR
                && CANCELAR_TODAS.matcher(PETICION.matcher(texto).replaceFirst("")).find());
    }

    private record Clausula(Accion accion, String texto) { }

    private static Clausula clausulaAutorizada(String herramienta, String mensaje, Rol rol) {
        Clausula seleccion = null;
        for (String frase : mensaje.split("(?iu)[.!?]\\s+|[;\\r\\n]+|\\b(?:y|e|o|adem[aá]s|tamb[ií]en|luego) "
                + "(?=(?:crea|programa|publica|edita|modifica|elimina|reprograma|solicita|cancela|muestra|consulta)\\w*\\b)")) {
            String texto = normalizar(frase);
            if (RECHAZO.matcher(texto).find()) return null;
            var prefijo = PETICION.matcher(texto);
            prefijo.find();
            if ((frase.stripLeading().startsWith("¿") || frase.stripTrailing().endsWith("?"))
                    && !PETICION_EN_PREGUNTA.matcher(texto).find()) continue;
            String peticion = texto.substring(prefijo.end());
            Accion accion = detectar(frase, null);
            if (accion == null) {
                for (var candidata : Accion.values()) {
                    if (!candidata.peticion.matcher(peticion).find()) continue;
                    if (accion != null) return null;
                    accion = candidata;
                }
            }
            if (accion == null && Accion.UNIRSE.herramienta.equals(herramienta) && esPeticionDeCupo(frase)) {
                accion = Accion.UNIRSE;
            }
            if (accion == null) continue;
            if (seleccion != null || accion.rol != rol || !accion.herramienta.equals(herramienta)) return null;
            seleccion = new Clausula(accion, frase);
        }
        return seleccion;
    }

    private static boolean figuraComoCampo(Campo campo, String valor, String texto) {
        String indicio = switch (campo) {
            case ORIGEN, ORIGEN_ACTUAL -> "(?:desde|origen(?: actual)?|(?:ruta|viaje|salida|cupo) de)";
            case DESTINO, DESTINO_ACTUAL -> "(?:a|hacia|destino(?: actual)?)";
            case COMPENSACION -> "(?:con|en|preferencia|compensacion)";
            case DIA -> "(?:el|dia)";
            default -> null;
        };
        return indicio != null && Pattern.compile("\\b" + indicio + "[: ]+(?:la |el )?"
                + Pattern.quote(normalizar(valor)) + "(?=$|[.,;!?]| (?:con\\b|del? \\d|el \\d|"
                + "a las?\\b|a\\b|hacia\\b|para\\b|por favor\\b))").matcher(texto).find();
    }

    public static Borrador siFaltanDatos(String mensaje, Rol rol, Clock clock) {
        var accion = detectar(mensaje, null);
        if (accion == null || accion.rol != rol || RECHAZO.matcher(normalizar(mensaje)).find()
                || VARIAS_ACCIONES.matcher(normalizar(mensaje)).find()) return null;
        var borrador = new Borrador(accion, extraer(mensaje, accion.campos, clock));
        return borrador.faltanDatos() ? borrador : null;
    }

    public static Borrador continuar(String mensaje, Borrador borrador, AclaracionAgente esquema, Contexto contexto, Clock clock) {
        if (borrador == null || esquema == null) return null;
        if (!borrador.opcionesViaje().isEmpty() && !cambiaDeTema(mensaje)) {
            var selector = esquema.campos().stream().filter(c -> c.id().equals(VIAJE.id())).findFirst().orElse(null);
            String referencia = selector == null ? null : Aclaraciones.EnvioAclaracion.valorSeleccionado(selector, mensaje);
            if (referencia != null) return borrador.continuar(Map.of(VIAJE.id(), referencia));
            var opciones = borrador.opcionesViaje().entrySet().stream()
                    .filter(e -> menciona(normalizar(mensaje), e.getValue().origen())
                            || menciona(normalizar(mensaje), e.getValue().conductorNombre())).toList();
            if (opciones.size() == 1) return borrador.continuar(Map.of(VIAJE.id(), opciones.getFirst().getKey()));
            return null;
        }
        String texto = normalizar(mensaje);
        var solicitados = borrador.accion.campos.stream()
                .filter(c -> esquema.campos().stream().anyMatch(f -> f.id().equals(c.id()))).toList();
        var ruta = esquema.campos().stream().filter(c -> c.id().equals(RUTA.id())).findFirst().orElse(null);
        if (ruta != null && borrador.rutaViaje() != null) {
            var estado = borrador.rutaViaje();
            if (SIGUIENTE_PAGINA.matcher(texto).matches())
                return estado.hayMas() ? borrador.conRutaViaje(estado.pagina(estado.pagina() + 1)) : null;
            if (PAGINA_ANTERIOR.matcher(texto).matches())
                return estado.pagina() > 0 ? borrador.conRutaViaje(estado.pagina(estado.pagina() - 1)) : null;
        }
        if (MISMO_ORIGEN.matcher(texto).matches()) {
            if (contexto == null || contexto.origen == null) return null;
            if (ruta != null) return borrador.buscarRuta(contexto.origen, null);
            var campo = solicitados.stream().filter(c -> c == ORIGEN || c == ORIGEN_ACTUAL).findFirst().orElse(null);
            return campo == null ? null : borrador.continuar(Map.of(campo.id(), contexto.origen));
        }
        if (cambiaDeTema(mensaje) || ConfirmationIntent.classify(mensaje) != ConfirmationIntent.OTRA
                || mensaje.contains("?") || mensaje.contains("¿")) return null;
        var valores = extraer(mensaje, solicitados, clock);
        var seleccion = new LinkedHashMap<String, String>();
        if (ruta != null && borrador.rutaViaje() != null) {
            var ordinal = RUTA_NUMERADA.matcher(texto);
            if (ordinal.matches()) {
                String valor = ordinal.group(1);
                int numero = Character.isDigit(valor.charAt(0)) ? Integer.parseInt(valor) : NUMEROS_RUTA.indexOf(valor) + 1;
                String opcion = ruta.opciones().stream().filter(o -> o.etiqueta().startsWith(numero + ". "))
                        .map(OpcionAclaracion::valor).findFirst().orElse(null);
                return opcion == null ? null : borrador.continuar(Map.of(RUTA.id(), opcion));
            }
            String opcion = ruta.opciones().stream().filter(o -> normalizar(o.etiqueta()).equals(texto))
                    .map(OpcionAclaracion::valor).findFirst().orElse(null);
            if (opcion != null) return borrador.continuar(Map.of(RUTA.id(), opcion));
            var filtros = extraer(mensaje, List.of(ORIGEN, DESTINO), clock);
            var explicita = RUTA_POR_TEXTO.matcher(mensaje.strip());
            String origen = filtros.get(ORIGEN);
            if (origen == null && explicita.matches()) origen = validar(ORIGEN, explicita.group(1), clock);
            if (origen == null && filtros.isEmpty() && valores.isEmpty() && !etiquetado(mensaje)
                    && textoAislado(mensaje, texto) && validar(FECHA, mensaje, clock) == null
                    && validar(HORA, mensaje, clock) == null) {
                origen = validar(ORIGEN, mensaje, clock);
            }
            if (origen != null || filtros.containsKey(DESTINO)) borrador = borrador.buscarRuta(origen, filtros.get(DESTINO));
            else ruta = null;
        }
        if (valores.isEmpty() && !etiquetado(mensaje) && solicitados.size() == 1) {
            var campo = solicitados.getFirst();
            var tipo = esquema.campos().stream().filter(c -> c.id().equals(campo.id())).findFirst().orElseThrow().tipo();
            var valor = switch (tipo) {
                case NUMBER, DATE, TIME, SELECT -> validar(campo, mensaje, clock);
                case TEXT, TEXTAREA -> textoAislado(mensaje, texto) ? validar(campo, mensaje, clock) : null;
            };
            if (valor != null) valores.put(campo, valor);
        }
        for (var campo : solicitados) {
            var valor = valores.get(campo);
            var descriptor = esquema.campos().stream().filter(c -> c.id().equals(campo.id())).findFirst().orElseThrow();
            if (valor != null && (descriptor.opciones() == null
                    || descriptor.opciones().stream().anyMatch(o -> o.valor().equalsIgnoreCase(valor)))) {
                seleccion.put(campo.id(), valor);
            }
        }
        return seleccion.isEmpty() && ruta == null ? null : borrador.continuar(seleccion);
    }

    private static boolean textoAislado(String mensaje, String texto) {
        return TEXTO_AISLADO.matcher(mensaje.strip()).matches() && !RESPUESTA_CONVERSACIONAL.matcher(texto).find();
    }

    public static boolean cambiaDeTema(String mensaje) {
        if (esPeticionDeCupo(mensaje)) return true;
        String texto = normalizar(mensaje);
        if (NUEVO_TEMA.matcher(texto).find()) return true;
        String peticion = PETICION.matcher(texto).replaceFirst("");
        for (var accion : Accion.values()) if (accion.peticion.matcher(peticion).find()) return true;
        return false;
    }

    private static Accion detectar(String mensaje, Contexto anterior) {
        String texto = normalizar(mensaje);
        if (mensaje.stripLeading().startsWith("¿") && !texto.startsWith("puedes ") && !texto.startsWith("podrias ")) return null;
        if (CAMBIAR_HORA.matcher(texto).matches()) return Accion.REPROGRAMAR;
        if (OTRA_CREACION.matcher(texto).matches() && anterior != null
                && (anterior.accion == Accion.CREAR_RUTA || anterior.accion == Accion.CREAR_VIAJE)) return anterior.accion;
        for (var accion : Accion.values()) if (accion.intencion.matcher(texto).find()) return accion;
        return null;
    }

    private static boolean etiquetado(String mensaje) {
        return ETIQUETA.matcher(mensaje.strip()).find();
    }

    private static EnumMap<Campo, String> extraer(String mensaje, List<Campo> campos, Clock clock) {
        var encontrados = new EnumMap<Campo, String>(Campo.class);
        var etiquetas = ETIQUETA.matcher(mensaje.strip());
        String texto = mensaje.strip();
        int inicio = -1;
        Campo campo = null;
        while (etiquetas.find()) {
            if (campo != null) agregar(encontrados, campo, texto.substring(inicio, etiquetas.start()), clock);
            String etiqueta = normalizar(etiquetas.group(1));
            campo = campos.stream().filter(c -> normalizar(c.obligatorio().etiqueta()).equals(etiqueta)).findFirst()
                    .orElseGet(() -> alias(etiqueta, campos));
            inicio = etiquetas.end();
        }
        if (campo != null) agregar(encontrados, campo, texto.substring(inicio), clock);
        var origen = ORIGEN_EXPLICITO.matcher(texto);
        var campoOrigen = campos.contains(ORIGEN) ? ORIGEN : ORIGEN_ACTUAL;
        if (campos.contains(campoOrigen) && !encontrados.containsKey(campoOrigen) && origen.find())
            agregar(encontrados, campoOrigen, origen.group(1), clock);
        if (campos.contains(DESTINO) && !encontrados.containsKey(DESTINO)) {
            var destino = DESTINO_VIAJE.matcher(texto);
            if (destino.find()) agregar(encontrados, DESTINO, destino.group(1), clock);
        }
        String normalizado = normalizar(texto);
        if (campos.contains(NUEVA_FECHA) || campos.contains(NUEVA_HORA)) {
            var horario = NUEVO_HORARIO.matcher(normalizado);
            if (horario.find()) {
                var nuevo = horario.group(1);
                if (campos.contains(NUEVA_FECHA) && !encontrados.containsKey(NUEVA_FECHA))
                    agregar(encontrados, NUEVA_FECHA, ContribucionIntent.sinHoras(nuevo), clock);
                if (campos.contains(NUEVA_HORA) && !encontrados.containsKey(NUEVA_HORA))
                    agregarHora(encontrados, NUEVA_HORA, nuevo);
                normalizado = normalizado.substring(0, horario.start());
            } else if (campos.contains(NUEVA_FECHA) && campos.contains(FECHA)
                    && !normalizado.matches(".*\\b(?:del? \\d+|actualmente|programad[oa] para)\\b.*")) {
                // Without a distinction between old and new, only labeled dates are safe.
                return encontrados;
            }
        }
        if (campos.contains(FECHA) && !encontrados.containsKey(FECHA))
            agregar(encontrados, FECHA, ContribucionIntent.sinHoras(normalizado), clock);
        if (campos.contains(HORA) && !encontrados.containsKey(HORA)) agregarHora(encontrados, HORA, normalizado);
        return encontrados;
    }

    private static void agregarHora(Map<Campo, String> datos, Campo campo, String texto) {
        try {
            var hora = ContribucionIntent.hora(texto);
            if (hora != null) datos.put(campo, hora.toString());
        } catch (java.time.DateTimeException invalida) {
            // The callback will request a corrected time.
        }
    }

    private static Campo alias(String etiqueta, List<Campo> campos) {
        Campo candidato = switch (etiqueta) {
            case "capacidad" -> CAPACIDAD;
            case "referencias", "punto de referencia" -> REFERENCIAS;
            case "preferencia" -> COMPENSACION;
            case "origen" -> ORIGEN_ACTUAL;
            case "destino" -> DESTINO_ACTUAL;
            case "dia" -> DIA;
            default -> null;
        };
        return candidato != null && campos.contains(candidato) ? candidato : null;
    }

    private static void agregar(Map<Campo, String> datos, Campo campo, String texto, Clock clock) {
        var valor = validar(campo, texto, clock);
        if (valor != null) datos.put(campo, valor);
    }

    private static String validar(Campo campo, String texto, Clock clock) {
        String valor = texto.strip().replaceAll("[.!?]+$", "").strip();
        if (valor.isEmpty() || AgentConversationStore.containsSecrets(valor)) return null;
        try {
            return switch (campo) {
                case CAPACIDAD -> entero(valor);
                case FECHA, NUEVA_FECHA -> java.util.Objects.toString(
                        ContribucionIntent.fecha(normalizar(valor), LocalDate.now(clock)), null);
                case HORA, NUEVA_HORA -> hora(valor);
                case COMPENSACION -> com.uniride.enums.TipoCompensacion.valueOf(normalizar(valor)
                        .replaceFirst("^(?:prefiero(?: compensar con)?|con|compensar con) +", "")
                        .toUpperCase(Locale.ROOT)).name();
                default -> valor;
            };
        } catch (java.time.DateTimeException | IllegalArgumentException invalido) {
            return null;
        }
    }

    private static String hora(String valor) {
        if (valor.matches("\\d{1,2}")) return LocalTime.of(Integer.parseInt(valor), 0).toString();
        if (valor.matches("\\d:\\d{2}")) valor = "0" + valor;
        return java.util.Objects.toString(ContribucionIntent.hora("a las " + normalizar(valor)), null);
    }

    private static String entero(String valor) {
        var numero = ENTERO.matcher(valor);
        if (!numero.matches()) return null;
        return switch (normalizar(numero.group(1))) {
            case "un", "uno", "una" -> "1";
            case "dos" -> "2";
            case "tres" -> "3";
            case "cuatro" -> "4";
            case "cinco" -> "5";
            case "seis" -> "6";
            default -> Integer.toString(Integer.parseInt(numero.group(1)));
        };
    }

}
