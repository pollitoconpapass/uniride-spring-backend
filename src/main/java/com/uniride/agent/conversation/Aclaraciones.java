package com.uniride.agent.conversation;

import static com.uniride.agent.dto.TipoCampoAclaracion.*;

import com.uniride.agent.dto.AclaracionAgente;
import com.uniride.agent.dto.CampoAclaracion;
import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.dto.OpcionAclaracion;
import com.uniride.agent.dto.OperacionResumen;
import com.uniride.agent.dto.TipoCampoAclaracion;
import com.uniride.agent.support.AgentProviderException;
import com.uniride.exceptions.CamposInvalidosException;
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
        PDF_ANIO("anio", "Año", NUMBER, null);

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
                case COMPENSACION, TIPO_ACTUAL, NUEVO_TIPO -> COMPENSACIONES;
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
                .map(Aclaraciones::normalizar).filter(valor -> !valor.isEmpty()).distinct().limit(2).count() == 2;
    }

    private static Optional<CampoAclaracion> opcionesUnicas(Discriminante criterio) {
        var valores = criterio.valores();
        if (valores.size() > 6 || valores.stream().anyMatch(valor -> blanco(valor)
                || (criterio.parcial() && (valor.contains("%") || valor.contains("_"))))) return Optional.empty();
        var normalizados = valores.stream().map(Aclaraciones::normalizar).toList();
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

    private static String normalizar(String valor) { return valor.strip().toLowerCase(Locale.ROOT); }

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
            if (!"ruta".equals(campo.id())) return valor;
            return campo.opciones().stream().filter(opcion -> opcion.valor().equals(valor)).findFirst()
                    .orElseThrow(EnvioAclaracion::invalido).etiqueta();
        }

        private static CamposInvalidosException invalido() {
            return new CamposInvalidosException("Revisa los campos de la aclaración e intenta de nuevo.");
        }
    }
}
