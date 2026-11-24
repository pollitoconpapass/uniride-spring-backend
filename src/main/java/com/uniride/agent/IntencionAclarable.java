package com.uniride.agent;

import static com.uniride.agent.Aclaraciones.Campo.*;

import com.uniride.agent.Aclaraciones.Campo;
import com.uniride.agent.dto.AclaracionAgente;
import com.uniride.agent.dto.CampoAclaracion;
import com.uniride.enums.Rol;
import java.text.Normalizer;
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
final class IntencionAclarable {
    private static final String INICIO = "^(?:por favor,? )?(?:ahora )?(?:(?:quiero|quisiera|necesito|puedes|podrias|ayudame a) )?";
    private static final Pattern OTRA_CREACION = Pattern.compile(INICIO + "cre(?:a|ar) (?:otr[oa]|un[oa] mas)$");
    private static final Pattern CAMBIAR_HORA = Pattern.compile(INICIO + "(?:cambia|cambiar|modifica|modificar) la hora$");
    private static final Pattern MISMO_ORIGEN = Pattern.compile(INICIO + "(?:usa|usar|conserva|conservar) el mismo origen$");
    private static final Pattern VARIAS_ACCIONES = Pattern.compile("[.!?]\\s+\\S|\\b(?:y|e|luego|ademas|tambien) "
            + "(?:crea|registra|muestra|busca|consulta|cancela|elimina|mira)\\w*\\b");
    private static final Pattern NUEVO_TEMA = Pattern.compile(INICIO + "(?:crea\\w*|publica\\w*|"
            + "elimina\\w*|registra\\w*|reprograma\\w*|edita\\w*|cambia\\w*|activa\\w*|desactiva\\w*|"
            + "cancela\\w*|muestra\\w*|busca\\w*|dime|consulta\\w*|descarga\\w*|exporta\\w*|"
            + "quiero|olvida|hola|gracias|(?:mis|mi) (?:rutas?|viajes?|solicitudes?|metodos?|estadisticas|historial))\\b");
    private static final Pattern ETIQUETA = Pattern.compile("(?iu)(?:^|(?:[;,]|\\b(?:con|y|e)\\b)\\s*)"
            + "(origen actual|destino actual|nuevo origen|puntos? de referencia|referencias|capacidad(?: m[aá]xima)?|"
            + "zonas sin parada|nota de contribuci[oó]n|nueva fecha|nueva hora|tipo de compensaci[oó]n|preferencia|"
            + "d[ií]a(?: de la semana)?|origen|destino|fecha|hora)\\s*(?::\\s*|\\s+)(?:de\\s+(?=[+-]?\\d))?");
    private static final Pattern ORIGEN_EXPLICITO = Pattern.compile("(?iu)\\b(?:desde|(?:ruta|viaje|salida) de) "
            + "([\\p{L}\\p{N}][\\p{L}\\p{N} .’'()-]{0,254}?)(?=$|[.,;!?]| (?:a |hacia |con |del? \\d|el \\d))");
    private static final Pattern NUEVO_HORARIO = Pattern.compile("\\bpara (?:el )?([^.!?]+)");
    private static final Pattern ENTERO = Pattern.compile("(?iu)^([+-]?\\d+|un[oa]?|dos|tres|cuatro|cinco|seis)"
            + "(?: pasajeros?)?$");

    private IntencionAclarable() { }

    enum Accion {
        CREAR_RUTA("crearRuta", Rol.CONDUCTOR, "(?:crea|crear|publica|publicar) (?:una |otra |mi |nueva )?ruta(?: mas)?",
                ORIGEN, REFERENCIAS, CAPACIDAD, ZONAS_SIN_PARADA, NOTA_CONTRIBUCION),
        EDITAR_RUTA("editarRuta", Rol.CONDUCTOR, "(?:edita|editar|actualiza|actualizar|modifica|modificar) (?:una |la |mi )?ruta",
                ORIGEN_ACTUAL, DESTINO_ACTUAL, NUEVO_ORIGEN, REFERENCIAS, CAPACIDAD, ZONAS_SIN_PARADA, NOTA_CONTRIBUCION),
        ELIMINAR_RUTA("eliminarRuta", Rol.CONDUCTOR, "(?:elimina|eliminar|borra|borrar) (?:una |la |mi )?ruta",
                ORIGEN_ACTUAL, DESTINO_ACTUAL),
        CREAR_VIAJE("crearViaje", Rol.CONDUCTOR, "(?:crea|crear|publica|publicar|programa|programar) (?:(?:un |otro |mi |nuevo )?viaje(?: mas)?|(?:una |otra |mi )?salida)",
                ORIGEN, DESTINO, FECHA, HORA),
        REPROGRAMAR("reprogramarViaje", Rol.CONDUCTOR, "(?:reprograma|reprogramar|cambia el horario de|cambiar el horario de) (?:(?:un |el |mi )?viaje|(?:una |la |mi )?salida)",
                ORIGEN, DESTINO, DIA, FECHA, HORA, NUEVA_FECHA, NUEVA_HORA),
        UNIRSE("solicitarUnirse", Rol.PASAJERO, "(?:solicita|solicitar|reserva|reservar|pide|pedir) (?:un )?cupo|un cupo|(?:unirme|uneme|sumarme) (?:a |al )?(?:un |el )?viaje",
                ORIGEN, DESTINO, DIA, FECHA, HORA, COMPENSACION),
        CANCELAR("cancelarSolicitud", Rol.PASAJERO, "(?:cancela|cancelar) (?:una |la |mi )?solicitud(?: pendiente)?",
                ORIGEN, DESTINO, DIA, FECHA, HORA);

        final String herramienta;
        final Rol rol;
        final Pattern intencion;
        final List<Campo> campos;

        Accion(String herramienta, Rol rol, String patron, Campo... campos) {
            this.herramienta = herramienta;
            this.rol = rol;
            this.intencion = Pattern.compile(INICIO + "(?:" + patron + ")(?:$| )");
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
    }

    record Contexto(Accion accion, String origen, String destino, String fecha, String hora) { }

    record Borrador(Accion accion, Map<Campo, String> datos) {
        Borrador { datos = Map.copyOf(datos); }

        Map<String, Object> argumentos() {
            var argumentos = new LinkedHashMap<String, Object>();
            datos.forEach((campo, valor) -> argumentos.put(accion.argumento(campo),
                    campo == CAPACIDAD ? Integer.valueOf(valor) : valor));
            return argumentos;
        }

        Borrador continuar(Map<String, String> campos) {
            var valores = new EnumMap<Campo, String>(Campo.class);
            valores.putAll(datos);
            accion.campos.forEach(campo -> {
                if (campos.containsKey(campo.id())) valores.put(campo, campos.get(campo.id()));
            });
            return new Borrador(accion, valores);
        }

        Contexto contexto() {
            return new Contexto(accion, datos.get(ORIGEN), datos.get(DESTINO),
                    datos.getOrDefault(NUEVA_FECHA, datos.get(FECHA)), datos.getOrDefault(NUEVA_HORA, datos.get(HORA)));
        }

        AclaracionAgente completar(AclaracionAgente esquema) {
            var obligatorios = switch (accion) {
                case CREAR_RUTA -> List.of(ORIGEN, REFERENCIAS, CAPACIDAD);
                case CREAR_VIAJE -> List.of(ORIGEN, FECHA, HORA);
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
                case CREAR_VIAJE -> !datos.keySet().containsAll(List.of(ORIGEN, FECHA, HORA));
                case REPROGRAMAR -> !datos.keySet().containsAll(List.of(NUEVA_FECHA, NUEVA_HORA));
                case UNIRSE -> !datos.containsKey(COMPENSACION);
                case EDITAR_RUTA, ELIMINAR_RUTA -> !datos.containsKey(ORIGEN_ACTUAL) && !datos.containsKey(DESTINO_ACTUAL);
                case CANCELAR -> false;
            };
        }
    }

    static Borrador inicioLocal(String mensaje, Contexto anterior, Clock clock) {
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
        if (!coincidencia.find()) return new Borrador(accion, Map.of());
        // The explicit bare request or labeled data can be resolved without asking the model.
        if (coincidencia.end() == texto.length()) return new Borrador(accion, Map.of());
        if (!etiquetado(mensaje) && !(accion == Accion.CREAR_RUTA && ORIGEN_EXPLICITO.matcher(mensaje).find())) return null;
        if (VARIAS_ACCIONES.matcher(texto).find()) return null;
        return new Borrador(accion, extraer(mensaje, accion.campos, clock));
    }

    static Borrador desdeTool(String herramienta, Map<?, ?> argumentos, String mensaje, Rol rol, Clock clock) {
        Accion accion = detectar(mensaje, null);
        if (accion == null || !accion.herramienta.equals(herramienta) || accion.rol != rol) return null;
        var datos = extraer(mensaje, accion.campos, clock);
        String texto = " " + normalizar(mensaje) + " ";
        for (var campo : accion.campos) {
            Object dato = argumentos.get(accion.argumento(campo));
            if (dato == null || datos.containsKey(campo)) continue;
            var valor = validar(campo, dato.toString(), clock);
            if (valor != null && Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(normalizar(valor))
                    + "(?![\\p{L}\\p{N}])").matcher(texto).find()) datos.put(campo, valor);
        }
        return new Borrador(accion, datos);
    }

    static Borrador siFaltanDatos(String mensaje, Rol rol, Clock clock) {
        var accion = detectar(mensaje, null);
        if (accion == null || accion.rol != rol) return null;
        var borrador = new Borrador(accion, extraer(mensaje, accion.campos, clock));
        return borrador.faltanDatos() ? borrador : null;
    }

    static Borrador continuar(String mensaje, Borrador borrador, AclaracionAgente esquema, Contexto contexto, Clock clock) {
        if (borrador == null || esquema == null) return null;
        String texto = normalizar(mensaje);
        var solicitados = borrador.accion.campos.stream()
                .filter(c -> esquema.campos().stream().anyMatch(f -> f.id().equals(c.id()))).toList();
        if (MISMO_ORIGEN.matcher(texto).matches()) {
            if (contexto == null || contexto.origen == null) return null;
            return solicitados.stream().filter(c -> c == ORIGEN || c == ORIGEN_ACTUAL).findFirst()
                    .map(c -> borrador.continuar(Map.of(c.id(), contexto.origen))).orElse(null);
        }
        if (NUEVO_TEMA.matcher(texto).find() || mensaje.contains("?") || mensaje.contains("¿")) return null;
        var valores = extraer(mensaje, solicitados, clock);
        boolean explicito = etiquetado(mensaje);
        if (valores.isEmpty() && solicitados.size() == 1) {
            var campo = solicitados.getFirst();
            var tipo = esquema.campos().getFirst().tipo();
            var valor = switch (tipo) {
                case NUMBER, DATE, TIME, SELECT -> validar(campo, mensaje, clock);
                case TEXT, TEXTAREA -> null;
            };
            if (valor != null) valores.put(campo, valor);
        }
        var seleccion = new LinkedHashMap<String, String>();
        for (var campo : solicitados) {
            var valor = valores.get(campo);
            var descriptor = esquema.campos().stream().filter(c -> c.id().equals(campo.id())).findFirst().orElseThrow();
            if (valor != null && (descriptor.opciones() == null
                    || descriptor.opciones().stream().anyMatch(o -> o.valor().equalsIgnoreCase(valor)))) {
                seleccion.put(campo.id(), valor);
            }
        }
        return seleccion.isEmpty() && !explicito ? null : borrador.continuar(seleccion);
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
        if (campos.contains(ORIGEN) && !encontrados.containsKey(ORIGEN) && origen.find())
            agregar(encontrados, ORIGEN, origen.group(1), clock);
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
            } else if (campos.contains(NUEVA_FECHA) && campos.contains(FECHA)) {
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

    private static String normalizar(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).strip().replaceAll("^[¿¡]+|[.!?]+$", "").strip();
    }
}
