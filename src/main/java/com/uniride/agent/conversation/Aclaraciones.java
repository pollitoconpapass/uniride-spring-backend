package com.uniride.agent.conversation;

import static com.uniride.agent.support.AgentText.normalizar;
import static com.uniride.agent.dto.TipoCampoAclaracion.*;

import com.uniride.agent.dto.AclaracionAgente;
import com.uniride.agent.dto.CampoAclaracion;
import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.dto.OpcionAclaracion;
import com.uniride.agent.dto.OperacionResumen;
import com.uniride.agent.dto.TipoCampoAclaracion;
import com.uniride.agent.support.AgentProviderException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.services.HorarioAcademicoService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;


/** All form fields and contexts are server-owned. Tools select just the unmet requirements. */
public final class Aclaraciones {
    private static final List<OpcionAclaracion> COMPENSACIONES = List.of(
            new OpcionAclaracion("DINERO", "Dinero"), new OpcionAclaracion("GASOLINA", "Gasolina"),
            new OpcionAclaracion("FAVOR", "Favor"));

    private Aclaraciones() { }

    public static AclaracionAgente crear(String operacion, CampoAclaracion... campos) {
        return new AclaracionAgente(1, null, operacion, contexto(operacion), List.of(campos), null, null, "campo");
    }

    public static AclaracionAgente alternativa(String operacion, Campo... campos) {
        return new AclaracionAgente(1, null, operacion, contexto(operacion),
                Arrays.stream(campos).map(c -> c.opcional("Completa al menos uno de estos campos.")).toList(),
                Arrays.stream(campos).map(Campo::id).toList(), null, "campo");
    }

    private static String contexto(String operacion) {
        return switch (operacion) {
            case "crearRuta" -> "Crear una ruta";
            case "editarRuta" -> "Editar una ruta";
            case "eliminarRuta" -> "Identificar la ruta que quieres eliminar";
            case "crearViaje" -> "Crear un viaje";
            case "reprogramarViaje" -> "Reprogramar un viaje";
            case "solicitarUnirse" -> "Solicitar un cupo";
            case "cancelarSolicitud" -> "Identificar la solicitud que quieres cancelar";
            case "registrarMetodoCompensacion" -> "Registrar un método de compensación";
            case "actualizarMetodoCompensacion" -> "Editar un método de compensación";
            case "cambiarEstadoMetodoCompensacion" -> "Cambiar el estado de un método de compensación";
            case "eliminarMetodoCompensacion" -> "Identificar el método que quieres eliminar";
            case "sugerirContribucionViaje" -> "Consultar una sugerencia de contribución";
            case "prepararDescargaHistorialPdf" -> "Descargar el historial en PDF";
            case "editarPerfil" -> "Editar tu perfil personal";
            case "editarInfoAdicional" -> "Editar tu información adicional";
            case "cambiarRol" -> "Cambiar tu rol activo";
            case "registrarCurso" -> "Registrar un curso";
            case "eliminarCurso" -> "Identificar el curso que quieres eliminar";
            default -> throw new IllegalArgumentException("Operación de aclaración inválida");
        };
    }

    public enum Campo {
        ORIGEN("origen", "Origen", TEXT, 255), DESTINO("destino", "Destino", TEXT, 255),
        RUTA("ruta", "Ruta registrada", SELECT, null),
        ORIGEN_ACTUAL("origenActual", "Origen actual", TEXT, 255),
        DESTINO_ACTUAL("destinoActual", "Destino actual", TEXT, 255),
        NUEVO_ORIGEN("nuevoOrigen", "Nuevo origen", TEXT, 255),
        REFERENCIAS("referencias", "Puntos de referencia", TEXTAREA, null),
        CAPACIDAD("capacidad", "Capacidad máxima", NUMBER, null),
        ZONAS_SIN_PARADA("zonasSinParada", "Zonas sin parada", TEXTAREA, null),
        NOTA_CONTRIBUCION("notaContribucion", "Nota de contribución", TEXTAREA, 255),
        DIA("dia", "Día de la semana", TEXT, 20), FECHA("fecha", "Fecha", DATE, null),
        HORA("hora", "Hora", TIME, null), NUEVA_FECHA("nuevaFecha", "Nueva fecha", DATE, null),
        NUEVA_HORA("nuevaHora", "Nueva hora", TIME, null),
        COMPENSACION("compensacion", "Tipo de compensación", SELECT, null),
        DESCRIPCION("descripcion", "Descripción", TEXTAREA, 255),
        TIPO_ACTUAL("tipoActual", "Tipo actual", SELECT, null),
        DESCRIPCION_ACTUAL("descripcionActual", "Descripción actual", TEXT, 255),
        NUEVO_TIPO("nuevoTipo", "Nuevo tipo", SELECT, null),
        NUEVA_DESCRIPCION("nuevaDescripcion", "Nueva descripción", TEXTAREA, 255),
        ESTADO("activo", "Acción", SELECT, null), PASAJEROS("pasajeros", "Cantidad de pasajeros", NUMBER, null),
        CONDUCTOR("conductor", "Nombre del conductor", TEXT, 120),
        PDF_DESDE("desde", "Fecha inicial", DATE, null), PDF_HASTA("hasta", "Fecha final", DATE, null),
        PDF_TIPO("tipo", "Rol del historial", SELECT, null), PDF_PERIODO("periodo", "Período", TEXT, 120),
        PDF_ANIO("anio", "Año", NUMBER, null),
        VIAJE("viaje", "Viaje disponible", SELECT, null),
        CURSO("curso", "Curso de tu horario", SELECT, null),
        ACCION_CURSO("accionCurso", "Acción para ese curso", SELECT, null),
        NOMBRE_CURSO("nombreCurso", "Nombre del curso", TEXT, 120),
        DIA_CURSO("dia", "Día de la semana", SELECT, null),
        HORA_INICIO("horaInicio", "Hora de inicio", TIME, null),
        HORA_FIN("horaFin", "Hora de fin", TIME, null),
        CAMPO_PERFIL("campoPerfil", "Dato del perfil", SELECT, null),
        NOMBRE("nombre", "Nombre", TEXT, 120), APELLIDOS("apellidos", "Apellidos", TEXT, 120),
        CARRERA("carrera", "Carrera", TEXT, 120), DISTRITO("distrito", "Distrito", TEXT, 120),
        UNIVERSIDAD("universidad", "Universidad", TEXT, 120),
        DIAS_DISPONIBLES("diasDisponibles", "Días disponibles", TEXT, 255),
        HORARIOS_DISPONIBLES("horariosDisponibles", "Horarios disponibles", TEXT, 255),
        INICIO_CLASES("fechaInicioClases", "Fecha de inicio de clases", DATE, null),
        FIN_CLASES("fechaFinClases", "Fecha de fin de clases", DATE, null),
        FAVORITO("metodoCompensacionFavorito", "Método de compensación favorito", SELECT, null),
        GUSTOS("gustos", "Gustos", TEXTAREA, 255), HOBBIES("hobbies", "Hobbies", TEXTAREA, 255),
        DATOS_CURIOSOS("datosCuriosos", "Datos curiosos", TEXTAREA, 255),
        ROL("rol", "Nuevo rol", SELECT, null);

        private final String id;
        private final String etiqueta;
        private final TipoCampoAclaracion tipo;
        private final Integer longitudMaxima;

        Campo(String id, String etiqueta, TipoCampoAclaracion tipo, Integer longitudMaxima) {
            this.id = id;
            this.etiqueta = etiqueta;
            this.tipo = tipo;
            this.longitudMaxima = longitudMaxima;
        }

        public String id() { return id; }
        public CampoAclaracion obligatorio() { return construir(true, null); }
        public CampoAclaracion opcional(String ayuda) { return construir(false, ayuda); }
        public CampoAclaracion conOpciones(List<OpcionAclaracion> opciones) {
            return new CampoAclaracion(id, etiqueta, SELECT, true, null, opciones, null, null, null, null);
        }

        private CampoAclaracion construir(boolean obligatorio, String ayuda) {
            var opciones = switch (this) {
                case COMPENSACION, TIPO_ACTUAL, NUEVO_TIPO, FAVORITO -> COMPENSACIONES;
                case DIA_CURSO -> HorarioAcademicoService.DIAS.stream()
                        .map(dia -> new OpcionAclaracion(dia, dia)).toList();
                case ROL -> List.of(new OpcionAclaracion("CONDUCTOR", "Conductor"),
                        new OpcionAclaracion("PASAJERO", "Pasajero"));
                case ACCION_CURSO -> List.of(new OpcionAclaracion("registrar", "Preparar el registro del curso"));
                case CAMPO_PERFIL -> camposPerfil().stream().map(campo -> new OpcionAclaracion(campo.id, campo.etiqueta)).toList();
                case ESTADO -> List.of(new OpcionAclaracion("activar", "Activar"),
                        new OpcionAclaracion("desactivar", "Desactivar"));
                case PDF_TIPO -> List.of(new OpcionAclaracion("todos", "Ambos roles"),
                        new OpcionAclaracion("conductor", "Conductor"), new OpcionAclaracion("pasajero", "Pasajero"));
                default -> null;
            };
            Integer maximo = tipo == NUMBER ? Integer.valueOf(this == PDF_ANIO ? 9999 : Integer.MAX_VALUE) : null;
            return new CampoAclaracion(id, etiqueta, tipo, obligatorio, null, opciones, tipo == NUMBER ? 1 : null,
                    maximo, longitudMaxima, ayuda);
        }
    }

    public static List<Campo> camposPerfil() {
        return List.of(Campo.NOMBRE, Campo.APELLIDOS, Campo.CARRERA, Campo.DISTRITO, Campo.UNIVERSIDAD,
                Campo.DIAS_DISPONIBLES, Campo.HORARIOS_DISPONIBLES, Campo.INICIO_CLASES, Campo.FIN_CLASES, Campo.FAVORITO);
    }

    private static final Pattern PREFIJO_CAMBIO = Pattern.compile(
            "^(?:por favor,? )?(?:(?:quiero|quisiera|necesito|puedes|podrias|ayudame a) )?");
    private static final Pattern HORAS_CURSO = Pattern.compile(
            "\\b(?:de|entre) (\\d{1,2}(?::\\d{2})?) (?:a|y) (\\d{1,2}(?::\\d{2})?)\\b");
    private static final Pattern NOMBRE_EN_HORARIO = Pattern.compile(
            "(?iu)\\b(?:agrega\\w*|a[ñn]ade\\w*|registra\\w*|quita\\w*|elimina\\w*|tengo) "
                    + "(?:(?:la|el) (?:asignatura|curso|clase) )?(.+?)(?= (?:a|de) mi horario"
                    + "| (?:los?|las?|del|de los|para el) (?:lunes|martes|mi[eé]rcoles|jueves|viernes|s[aá]bado|domingo)\\b|$)");

    public static AgentConversationStore.CambioPersonal cambioDesdeTool(String herramienta, Map<?, ?> argumentos, String mensaje) {
        String texto = normalizar(mensaje);
        if (IntencionAclarable.bloqueaPreparacion(mensaje)
                || texto.matches("(?s).*\\b(?:no|nunca|ignora|simula|herramienta|instrucciones|argumentos)\\b.*")) return null;
        String pedido = PREFIJO_CAMBIO.matcher(texto).replaceFirst("");
        boolean esCurso = List.of("registrarCurso", "eliminarCurso").contains(herramienta);
        var campos = switch (herramienta) {
            case "editarPerfil" -> camposPerfil();
            case "editarInfoAdicional" -> List.of(Campo.GUSTOS, Campo.HOBBIES, Campo.DATOS_CURIOSOS);
            case "cambiarRol" -> List.of(Campo.ROL);
            case "registrarCurso" -> List.of(Campo.NOMBRE_CURSO, Campo.DIA_CURSO, Campo.HORA_INICIO, Campo.HORA_FIN);
            case "eliminarCurso" -> List.of(Campo.NOMBRE_CURSO, Campo.DIA_CURSO, Campo.HORA_INICIO);
            default -> throw new IllegalArgumentException("Operación personal desconocida");
        };
        var mencionados = campos.stream().filter(c -> menciona(texto, patronCampo(c))).toList();
        boolean dominioAcademico = menciona(texto, "cursos?|clases?|asignaturas?|horario(?: academico)?");
        boolean nombreExpreso = argumentos.get("nombre") instanceof String nombre
                && (pedido.matches("(?:registr|inscrib)\\w* " + Pattern.quote(normalizar(nombre)))
                || menciona(texto, Pattern.quote(normalizar(nombre))) && HorarioAcademicoService.DIAS.stream()
                    .anyMatch(dia -> menciona(texto, normalizar(dia))));
        boolean descripcionCurso = "registrarCurso".equals(herramienta)
                && pedido.startsWith("tengo ") && HORAS_CURSO.matcher(texto).find();
        boolean autorizado = switch (herramienta) {
            case "editarPerfil" -> inicia(pedido, "edit|actualiz|cambi|modific|corrig")
                    && (!mencionados.isEmpty() || !dominioAcademico
                        && menciona(texto, "perfil|datos|info(?:rmacion)? personal|disponibilidad"));
            case "editarInfoAdicional" -> inicia(pedido, "agreg|anad|edit|actualiz|cambi|modific|pon")
                    && (!mencionados.isEmpty() || menciona(texto, "informacion adicional"));
            case "cambiarRol" -> inicia(pedido, "cambi|pas|us|utiliz|conviert|convert|quiero") && !mencionados.isEmpty();
            case "registrarCurso" -> (inicia(pedido, "agreg|anad|registr|inscrib") && (dominioAcademico || nombreExpreso)
                    || descripcionCurso) && !CompensationIntent.contextoGestion(mensaje)
                    && !IntencionAclarable.esPeticionDeCupo(mensaje);
            case "eliminarCurso" -> inicia(pedido, "elimin|borr|quit|retir") && dominioAcademico
                    && !CompensationIntent.contextoGestion(mensaje);
            default -> false;
        };
        if (!autorizado) return null;
        Map<String, Object> verificados = new LinkedHashMap<>();
        for (var campo : esCurso || "cambiarRol".equals(herramienta) ? campos : mencionados) {
            String clave = campo == Campo.NOMBRE_CURSO ? "nombre" : campo.id();
            if (argumentos.get(clave) instanceof String valor && AclaracionAgente.textoSeguro(valor, 255)
                    && menciona(texto, Pattern.quote(normalizar(valor)))
                    && (campo == Campo.ROL || !normalizar(valor).matches(patronCampo(campo)))
                    && (campo != Campo.NOMBRE_CURSO || !normalizar(valor).matches("(?:curso|clase|asignatura)"))) {
                verificados.put(clave, valor);
            }
        }
        if (esCurso) {
            var dias = Campo.DIA_CURSO.obligatorio().opciones().stream()
                    .filter(o -> menciona(texto, normalizar(o.valor()))).toList();
            if (dias.size() == 1) verificados.put("dia", dias.getFirst().valor());
            var nombre = NOMBRE_EN_HORARIO.matcher(mensaje.strip().replaceFirst("[.!?]+$", ""));
            if (nombre.find()) {
                String valor = nombre.group(1).strip();
                String clave = normalizar(valor);
                if (!clave.matches("(?:(?:un|una|el|la) )?(?:curso|clase|asignatura)(?: .*|$)")
                        && !clave.matches("(?:del|de los|para el) .*")
                        && !menciona(clave, "horario|viaje|metodo|curso del")
                        && AclaracionAgente.textoSeguro(valor, 120)) verificados.putIfAbsent("nombre", valor);
            }
            var horas = HORAS_CURSO.matcher(texto);
            if ("registrarCurso".equals(herramienta) && horas.find()) {
                try {
                    verificados.put("horaInicio", horaBreve(horas.group(1)));
                    verificados.put("horaFin", horaBreve(horas.group(2)));
                } catch (java.time.DateTimeException invalida) {
                    verificados.remove("horaInicio");
                    verificados.remove("horaFin");
                }
            }
            if (descripcionCurso) {
                if (!verificados.containsKey("dia") || !verificados.containsKey("nombre")) return null;
                verificados.put("accionCurso", "pendiente");
            }
        } else if ("editarInfoAdicional".equals(herramienta)) {
            verificados.put("agregar", inicia(pedido, "agreg|anad"));
        } else if ("cambiarRol".equals(herramienta) && verificados.get("rol") instanceof String rol
                && !menciona(texto, "(?:a|como|ser|modo|rol(?: de)?)\\s+" + Pattern.quote(normalizar(rol)))) {
            verificados.remove("rol");
        }
        if (!esCurso && !"cambiarRol".equals(herramienta) && mencionados.size() == 1
                && !verificados.containsKey(mencionados.getFirst().id())) {
            verificados.put("campoPerfil", mencionados.getFirst().id());
        }
        return new AgentConversationStore.CambioPersonal(herramienta, verificados);
    }

    private static boolean inicia(String texto, String verbos) {
        return texto.matches("^(?:" + verbos + ")\\w*\\b.*");
    }

    private static boolean menciona(String texto, String expresion) {
        return Pattern.compile("(?<![\\p{L}\\d])(?:" + expresion + ")(?![\\p{L}\\d])").matcher(texto).find();
    }

    private static String patronCampo(Campo campo) {
        return switch (campo) {
            case APELLIDOS -> "apellidos?";
            case DIAS_DISPONIBLES -> "dias disponibles";
            case HORARIOS_DISPONIBLES -> "horarios disponibles|disponibilidad";
            case INICIO_CLASES -> "inicio de clases";
            case FIN_CLASES -> "fin de clases";
            case FAVORITO -> "(?:metodo(?: de compensacion)?|compensacion) favorit[oa]";
            case GUSTOS -> "gustos?|me gusta";
            case HOBBIES -> "hobb(?:y|ies)|pasatiempos?";
            case ROL -> "rol|conductor[ae]?|pasajer[oa]";
            default -> Pattern.quote(normalizar(campo.etiqueta));
        };
    }

    public static AgentConversationStore.CambioPersonal continuarCambio(String mensaje, Map<String, String> campos,
            AclaracionAgente activa, AgentConversationStore.Draft anterior) {
        if (!(anterior instanceof AgentConversationStore.CambioPersonal cambio) || activa == null
                || !activa.operacion().equals(cambio.herramienta())) return null;
        if (campos == null) {
            if (IntencionAclarable.cambiaDeTema(mensaje) || IntencionAclarable.bloqueaPreparacion(mensaje)
                    || mensaje.contains("?") || ConfirmationIntent.classify(mensaje) != ConfirmationIntent.OTRA) return null;
            campos = EnvioAclaracion.leerTexto(activa, mensaje);
            if (campos.isEmpty()) return null;
        }
        var valores = new LinkedHashMap<>(cambio.argumentos());
        campos.forEach((campo, valor) -> valores.put("nombreCurso".equals(campo) ? "nombre" : campo, valor));
        return new AgentConversationStore.CambioPersonal(cambio.herramienta(), valores);
    }

    public static AclaracionAgente precisarCambio(AclaracionAgente descriptor, AgentConversationStore.CambioPersonal cambio) {
        if (cambio == null || !descriptor.operacion().equals(cambio.herramienta())
                || !(cambio.argumentos().get("campoPerfil") instanceof String id)) return descriptor;
        boolean inicial = descriptor.campos().stream().anyMatch(c -> "campoPerfil".equals(c.id()))
                || "editarInfoAdicional".equals(cambio.herramienta()) && descriptor.alMenosUno() != null;
        if (!inicial) return descriptor;
        return Arrays.stream(Campo.values()).filter(c -> c.id().equals(id)).findFirst()
                .map(c -> crear(cambio.herramienta(), c.obligatorio()).conRespuestaBreve("valor"))
                .orElse(descriptor);
    }

    private static String horaBreve(String texto) {
        return LocalTime.parse(texto.matches("\\d{1,2}") ? "%02d:00".formatted(Integer.parseInt(texto))
                : texto.matches("\\d:\\d{2}") ? "0" + texto : texto).toString();
    }

    private static final int LONGITUD_FILTRO = 255;

    public static OperacionResumen faltantes(String operacion, List<CampoAclaracion> campos) {
        String descripcion = campos.stream().map(CampoAclaracion::etiqueta)
                .map(etiqueta -> etiqueta.toLowerCase(Locale.ROOT)).collect(Collectors.joining(", "));
        return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null, "Indica " + descripcion + ".")
                .conAclaracion(Aclaraciones.crear(operacion, campos.toArray(CampoAclaracion[]::new)));
    }

    public static List<CampoAclaracion> filtrosDeRuta(String origen, String destino, Campo campoOrigen, Campo campoDestino) {
        var correcciones = new ArrayList<CampoAclaracion>();
        if (largo(origen)) correcciones.add(campoOrigen.obligatorio());
        if (largo(destino)) correcciones.add(campoDestino.obligatorio());
        return correcciones;
    }

    public record Discriminante(Campo campo, List<String> valores, boolean parcial) { }

    public static OperacionResumen aclararAmbiguedad(String operacion, OperacionResumen resumen, boolean todosVisibles,
                                              List<Discriminante> posibles) {
        var discriminantes = posibles.stream().filter(Aclaraciones::diferentes).toList();
        if (todosVisibles) {
            for (var criterio : discriminantes) {
                var opciones = opcionesUnicas(criterio);
                if (opciones.isPresent())
                    return resumen.conAclaracion(Aclaraciones.crear(operacion, opciones.get()).conOpcionesRapidas());
            }
        }
        if (discriminantes.isEmpty()) return resumen;
        if (discriminantes.size() == 1)
            return resumen.conAclaracion(Aclaraciones.crear(operacion, discriminantes.getFirst().campo().obligatorio()));
        return resumen.conAclaracion(Aclaraciones.alternativa(operacion,
                discriminantes.stream().map(Discriminante::campo).toArray(Campo[]::new)));
    }

    private static boolean diferentes(Discriminante criterio) {
        return criterio.valores().stream().filter(Objects::nonNull)
                .map(Aclaraciones::claveDeOpcion).filter(valor -> !valor.isEmpty()).distinct().limit(2).count() == 2;
    }

    private static Optional<CampoAclaracion> opcionesUnicas(Discriminante criterio) {
        var valores = criterio.valores();
        if (valores.size() > 6 || valores.stream().anyMatch(valor -> blanco(valor)
                || (criterio.parcial() && (valor.contains("%") || valor.contains("_"))))) return Optional.empty();
        var normalizados = valores.stream().map(Aclaraciones::claveDeOpcion).toList();
        boolean unicos = normalizados.stream().allMatch(valor -> normalizados.stream()
                .filter(otro -> criterio.parcial() ? otro.contains(valor) : otro.equals(valor)).limit(2).count() == 1);
        if (!unicos) return Optional.empty();
        try {
            return Optional.of(criterio.campo().conOpciones(valores.stream()
                    .map(valor -> new OpcionAclaracion(valor.strip(), valor.strip())).toList()));
        } catch (IllegalArgumentException valorNoPublicable) {
            return Optional.empty();
        }
    }

    private static String claveDeOpcion(String valor) { return valor.strip().toLowerCase(Locale.ROOT); }

    public static boolean largo(String valor) { return valor != null && valor.length() > LONGITUD_FILTRO; }

    private static boolean blanco(String value) { return value == null || value.isBlank(); }
    public record Salida(String origen, String destino, LocalDate fecha, LocalTime hora) { }

    public static OperacionResumen salidasAmbiguas(String operacion, OperacionResumen resumen, boolean todosVisibles,
                                                     List<Salida> salidas) {
        return aclararAmbiguedad(operacion, resumen, todosVisibles, List.of(
                new Discriminante(Campo.FECHA, salidas.stream().map(v -> Objects.toString(v.fecha(), null)).toList(), false),
                new Discriminante(Campo.HORA, salidas.stream().map(v -> Objects.toString(v.hora(), null)).toList(), false),
                new Discriminante(Campo.ORIGEN, salidas.stream().map(Salida::origen).toList(), true),
                new Discriminante(Campo.DESTINO, salidas.stream().map(Salida::destino).toList(), true)));
    }

    public static List<CampoAclaracion> filtrosViaje(String origen, String destino, String dia) {
        var correcciones = filtrosDeRuta(origen, destino, Campo.ORIGEN, Campo.DESTINO);
        if (dia != null && dia.length() > LONGITUD_FILTRO) correcciones.add(Campo.DIA.obligatorio());
        return correcciones;
    }

    public static final class EnvioAclaracion {
        private static final Pattern PREFIJO = Pattern.compile("(?iu)^datos de aclaraci[oó]n\\b");
        private static final Pattern SOBRE = Pattern.compile("(?isu)^datos de aclaraci[oó]n ([0-9a-f-]{36}):\\s*(.+)$");
        private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

        private EnvioAclaracion() { }

        public static Map<String, String> leer(String mensaje, AclaracionAgente activa) {
            if (!PREFIJO.matcher(mensaje.strip()).find()) return null;
            var match = SOBRE.matcher(mensaje.strip());
            if (!match.matches() || activa == null || !activa.id().toString().equalsIgnoreCase(match.group(1))) {
                throw new AgentProviderException(HttpStatus.CONFLICT, "CLARIFICATION_EXPIRED",
                        "Esta aclaración ya no está activa. Vuelve a indicar qué quieres hacer.");
            }
            JsonNode datos;
            try {
                datos = JSON.readTree(match.group(2));
            } catch (RuntimeException formato) {
                throw invalido();
            }
            if (!datos.isObject() || datos.isEmpty() || datos.size() > activa.campos().size()) throw invalido();
            var campos = new LinkedHashMap<String, String>();
            for (var entrada : datos.properties()) {
                var campo = activa.campos().stream().filter(c -> c.id().equals(entrada.getKey())).findFirst()
                        .orElseThrow(EnvioAclaracion::invalido);
                campos.put(campo.id(), validar(campo, entrada.getValue()));
            }
            return Map.copyOf(campos);
        }

        public static Map<String, String> leerTexto(AclaracionAgente activa, String mensaje) {
            var campos = new LinkedHashMap<String, String>();
            var entradas = mensaje.strip().split("\\s*[;\\r\\n]+\\s*");
            for (String entrada : entradas) {
                var partes = entrada.split(":", 2);
                var campo = activa.campos().stream().filter(c -> partes.length == 2
                        && (normalizar(c.etiqueta()).equals(normalizar(partes[0]))
                        || normalizar(c.id()).equals(normalizar(partes[0])))).findFirst().orElse(null);
                if (campo != null) agregarTexto(campos, campo, partes[1].strip());
            }
            if (campos.isEmpty() && entradas.length == 1) {
                if (activa.campos().size() == 1) agregarTexto(campos, activa.campos().getFirst(), mensaje.strip());
                else {
                    for (var campo : activa.campos()) {
                        if (campo.tipo() == SELECT || campo.tipo() == TIME || campo.tipo() == DATE) {
                            agregarTexto(campos, campo, mensaje.strip());
                        }
                    }
                    if (campos.size() > 1) return Map.of();
                    if (campos.isEmpty()) {
                        var libres = activa.campos().stream().filter(c -> c.tipo() == TEXT || c.tipo() == TEXTAREA).toList();
                        if (libres.size() == 1 && !mensaje.strip().matches("[\\d\\s:]+")) {
                            agregarTexto(campos, libres.getFirst(), mensaje.strip());
                        }
                    }
                }
            }
            return Map.copyOf(campos);
        }

        private static void agregarTexto(Map<String, String> campos, CampoAclaracion campo, String texto) {
            try {
                if (campo.tipo() == SELECT) texto = valorSeleccionado(campo, texto);
                if (campo.tipo() == TIME) texto = horaBreve(texto);
                if (texto != null) campos.put(campo.id(), validar(campo, JSON.valueToTree(texto)));
            } catch (CamposInvalidosException | java.time.DateTimeException invalido) {
                // Keep the field open if text or voice does not provide a valid value.
            }
        }

        public static String valorSeleccionado(CampoAclaracion campo, String mensaje) {
            String texto = normalizar(mensaje).replaceFirst("^(?:el|la) ", "");
            var posicion = Pattern.compile("^(?:opcion )?(10|[1-9])$").matcher(texto);
            int indice = posicion.matches() ? Integer.parseInt(posicion.group(1)) - 1
                    : List.of("primer", "segund", "tercer", "cuart", "quint", "sext", "septim", "octav", "noven", "decim")
                        .indexOf(texto.replaceFirst("^(?:opcion )", "").replaceFirst("[oa](?: opcion)?$", ""));
            if (indice >= 0 && indice < campo.opciones().size()) return campo.opciones().get(indice).valor();
            var coincidencias = campo.opciones().stream()
                    .filter(o -> normalizar(o.etiqueta()).equals(texto) || normalizar(o.valor()).equals(texto)).toList();
            return coincidencias.size() == 1 ? coincidencias.getFirst().valor() : null;
        }

        private static String validar(CampoAclaracion campo, JsonNode dato) {
            if (!dato.isString() && !(dato.isIntegralNumber() && dato.canConvertToInt())) throw invalido();
            String texto = dato.asText().strip();
            AgentConversationStore.validateNoSecrets(texto);
            if (texto.isBlank()) throw invalido();
            try {
                return switch (campo.tipo()) {
                    case NUMBER -> {
                        int numero = Integer.parseInt(texto);
                        if (campo.minimo() != null && numero < campo.minimo()
                                || campo.maximo() != null && numero > campo.maximo()) throw invalido();
                        yield Integer.toString(numero);
                    }
                    case DATE -> LocalDate.parse(texto).toString();
                    case TIME -> LocalTime.parse(texto).toString();
                    case SELECT -> campo.opciones().stream().filter(o -> o.valor().equals(texto)).findFirst()
                            .orElseThrow(EnvioAclaracion::invalido).valor();
                    case TEXT, TEXTAREA -> {
                        if (!dato.isString() || campo.longitudMaxima() != null && texto.length() > campo.longitudMaxima())
                            throw invalido();
                        yield texto;
                    }
                };
            } catch (java.time.DateTimeException | NumberFormatException formato) {
                throw invalido();
            }
        }

        public static String paraHistorial(AclaracionAgente esquema, Map<String, String> campos) {
            return esquema.campos().stream().filter(c -> campos.containsKey(c.id()))
                    .map(c -> c.etiqueta() + ": " + valorParaHistorial(c, campos.get(c.id())))
                    .collect(java.util.stream.Collectors.joining(", "));
        }

        private static String valorParaHistorial(CampoAclaracion campo, String valor) {
            if (!List.of("ruta", "viaje", "curso").contains(campo.id())) return valor;
            return campo.opciones().stream().filter(opcion -> opcion.valor().equals(valor)).findFirst()
                    .orElseThrow(EnvioAclaracion::invalido).etiqueta();
        }

        private static CamposInvalidosException invalido() {
            return new CamposInvalidosException("Revisa los campos de la aclaración e intenta de nuevo.");
        }
    }
}
