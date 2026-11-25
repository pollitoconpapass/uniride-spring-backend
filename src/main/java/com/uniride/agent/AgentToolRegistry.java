package com.uniride.agent;

import com.uniride.agent.confirmation.EstadoConfirmacion;
import com.uniride.agent.confirmation.IntentoPreparacion;
import static com.uniride.agent.tools.ContribucionAgentTools.CONTEXTO_FILTROS;
import static com.uniride.agent.tools.ContribucionAgentTools.NOMBRE;
import static com.uniride.agent.tools.ReportesAgentTools.CONTEXTO_FILTROS_PDF;
import static com.uniride.agent.tools.ReportesAgentTools.DESCARGA_HISTORIAL_PDF;

import com.uniride.agent.dto.*;
import com.uniride.agent.tools.*;
import com.uniride.enums.Rol;
import java.time.Clock;
import java.util.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Each turn owns its receipts and budget; nothing is shared between users or threads. */
@Component
public class AgentToolRegistry {
    private static final Set<String> PREPARATIONS = Set.of("crearRuta", "crearViaje", "solicitarUnirse",
            "registrarMetodoCompensacion", "actualizarMetodoCompensacion", "cambiarEstadoMetodoCompensacion",
            "eliminarMetodoCompensacion", "editarRuta", "eliminarRuta", "reprogramarViaje", "cancelarSolicitud",
            "editarPerfil", "editarInfoAdicional", "cambiarRol", "registrarCurso", "eliminarCurso",
            "aceptarSolicitud", "rechazarSolicitud", "rechazarSolicitudesPendientes",
            "confirmarViaje", "iniciarViaje", "completarViaje", "cancelarViaje");
    private static final Set<String> RUTAS_Y_VIAJES_ACLARABLES = Set.of("crearRuta", "editarRuta", "eliminarRuta",
            "crearViaje", "reprogramarViaje", "solicitarUnirse", "cancelarSolicitud");
    private static final Set<String> RECOVERABLE_COMPENSATION = Set.of("registrarMetodoCompensacion", "cambiarEstadoMetodoCompensacion",
            "actualizarMetodoCompensacion", "eliminarMetodoCompensacion");
    private static final Set<String> CONDUCTOR_TOOLS = Set.of("consultarMisRutas", "consultarMisViajes", "crearRuta",
            "editarRuta", "eliminarRuta", "crearViaje", "reprogramarViaje", "consultarRankingMisRutas");
    private static final Set<String> PASAJERO_TOOLS = Set.of("buscarViajes", "consultarMisSolicitudes", "solicitarUnirse",
            "cancelarSolicitud");
    private static final String OWN_METHODS = "consultarMisMetodosCompensacion";
    private static final ToolMetadata RETURN_DIRECT = ToolMetadata.builder().returnDirect(true).build();
    private static final String NO_ARGUMENTS = "{}";
    private final ObjectMapper mapper;
    private final ToolCallback[] callbacks;

    public AgentToolRegistry(ObjectMapper mapper, ViajeAgentTools viajes, RutaAgentTools rutas,
                             MetodoCompensacionAgentTools metodos, ReportesAgentTools reportes,
                             ContribucionAgentTools contribucion) {
        this(mapper, ToolCallbacks.from(viajes, rutas, metodos, reportes, contribucion));
    }

    @Autowired
    public AgentToolRegistry(ObjectMapper mapper, ViajeAgentTools viajes, RutaAgentTools rutas,
                             MetodoCompensacionAgentTools metodos, ReportesAgentTools reportes,
                             ContribucionAgentTools contribucion, PerfilAgentTools perfiles,
                             UsuarioAgentTools usuarios, HorarioAcademicoAgentTools horarios,
                             SolicitudesConductorAgentTools solicitudes, GestionViajeAgentTools gestion) {
        this(mapper, ToolCallbacks.from(viajes, rutas, metodos, reportes, contribucion,
                perfiles, usuarios, horarios, solicitudes, gestion));
    }

    AgentToolRegistry(ObjectMapper mapper, ToolCallback... callbacks) {
        this.mapper = mapper;
        this.callbacks = callbacks;
    }

    public Turn newTurn() { return newTurn(null); }

    Turn newTurn(HistorialPdfIntent.Solicitud historialPdf) {
        return newTurn(historialPdf, CompensationIntent.classify("", null), false);
    }

    Turn newTurn(HistorialPdfIntent.Solicitud historialPdf, CompensationIntent.Decision compensation, boolean solicitudViaje) {
        return newTurn(historialPdf, compensation, solicitudViaje, null);
    }

    Turn newTurn(HistorialPdfIntent.Solicitud historialPdf, CompensationIntent.Decision compensation,
                 boolean solicitudViaje, ContribucionFiltros filtrosContribucion) {
        return new Turn(historialPdf, compensation, solicitudViaje, filtrosContribucion);
    }

    public final class Turn {
        private final HistorialPdfIntent.Solicitud historialPdf;
        private final CompensationIntent.Decision compensation;
        private final boolean solicitudViaje;
        private final ContribucionFiltros filtrosContribucion;
        private int calls;
        private final List<ResultadoAgente> results = new ArrayList<>();
        private final Map<String, String> receipts = new HashMap<>();
        private final List<ViajeResumen> viajes = new ArrayList<>();
        private final List<RutaResumen> rutas = new ArrayList<>();
        private final List<MetodoCompensacionResumen> metodos = new ArrayList<>();
        private OperacionResumen operation;
        private AclaracionAgente camposOperacion;
        private ContribucionResultado contribucion;
        private boolean contribucionInvocada;
        private boolean prepared;
        private boolean persistenciaIntentada;
        private String solicitudBloqueada;
        private String mensajeUsuario;
        private Rol rolUsuario;
        private Clock reloj;
        private IntencionAclarable.Borrador rutaOViaje;
        private CoordinacionConductorIntent.Turno coordinacion;

        private Turn(HistorialPdfIntent.Solicitud historialPdf, CompensationIntent.Decision compensation,
                     boolean solicitudViaje, ContribucionFiltros filtrosContribucion) {
            this.historialPdf = historialPdf;
            this.compensation = compensation;
            this.solicitudViaje = solicitudViaje;
            this.filtrosContribucion = filtrosContribucion;
        }

        public ToolCallback[] callbacks() {
            return callbacks(null, false);
        }

        void contextoAclaracion(String mensaje, Rol rol, Clock clock) {
            this.mensajeUsuario = mensaje;
            this.rolUsuario = rol;
            this.reloj = clock;
        }

        boolean tieneHerramienta(String nombre) {
            return Arrays.stream(AgentToolRegistry.this.callbacks).anyMatch(c -> c.getToolDefinition().name().equals(nombre));
        }

        void procesar(IntencionAclarable.Borrador borrador) {
            rutaOViaje = borrador;
            String nombre = borrador.accion().herramienta;
            execute(callback(nombre), mapper.writeValueAsString(borrador.argumentos()), new ToolContext(Map.of()));
        }

        void procesar(CoordinacionConductorIntent.Pedido pedido) {
            coordinacion = new CoordinacionConductorIntent.Turno(pedido);
            execute(callback(pedido.accion().herramienta()), NO_ARGUMENTS,
                    new ToolContext(Map.of(CoordinacionConductorIntent.CONTEXTO, coordinacion)));
        }

        CoordinacionConductorIntent.Sesion coordinacion() { return coordinacion.sesion(); }

        IntencionAclarable.Borrador rutaOViaje() { return rutaOViaje; }

        boolean propuestaPendiente() {
            return operation != null && operation.estado() == EstadoOperacion.CONFIRMACION_REQUERIDA
                    && operation.confirmacion() != null && operation.confirmacion().estado() == EstadoConfirmacion.PENDIENTE;
        }

        boolean tieneAclaracion() { return aclaracion() != null; }

        boolean preparacionIncierta() { return persistenciaIntentada && !propuestaPendiente(); }

        ToolCallback[] callbacks(Rol role, boolean terminalResults) {
            return Arrays.stream(AgentToolRegistry.this.callbacks)
                    .filter(delegate -> available(delegate.getToolDefinition().name(), role))
                    .map(delegate -> new ToolCallback() {
                public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
                public ToolMetadata getToolMetadata() {
                    String name = getToolDefinition().name();
                    return terminalResults && (PREPARATIONS.contains(name) || OWN_METHODS.equals(name))
                            ? RETURN_DIRECT : delegate.getToolMetadata();
                }
                public String call(String input) { return call(input, new ToolContext(Map.of())); }
                public String call(String input, ToolContext context) {
                    return execute(delegate, input, context, role != null);
                }
            }).toArray(ToolCallback[]::new);
        }

        private boolean available(String name, Rol role) {
            if (CoordinacionConductorIntent.esHerramienta(name)) return false;
            if (NOMBRE.equals(name) || filtrosContribucion != null)
                return NOMBRE.equals(name) && filtrosContribucion != null;
            if (role == null) return true;
            if (CONDUCTOR_TOOLS.contains(name) && role != Rol.CONDUCTOR) return false;
            if (PASAJERO_TOOLS.contains(name) && role != Rol.PASAJERO) return false;
            if (RECOVERABLE_COMPENSATION.contains(name)) return false;
            if (compensation.blockPreparations() && PREPARATIONS.contains(name)) return false;
            return !DESCARGA_HISTORIAL_PDF.equals(name) || (historialPdf != null && historialPdf.filtros() != null);
        }

        synchronized void consultOwnMethods() {
            recoverRead(OWN_METHODS);
        }

        synchronized void recoverReports(Set<AgentReportIntent> requested) {
            for (AgentReportIntent report : requested) {
                recoverRead(report.toolName());
            }
            if (historialPdf != null && historialPdf.filtros() != null) recoverRead(DESCARGA_HISTORIAL_PDF);
        }

        private void recoverRead(String name) {
            if (receipts.containsKey(name + NO_ARGUMENTS)) return;
            execute(callback(name), NO_ARGUMENTS, new ToolContext(Map.of()));
        }

        synchronized void recoverContribucion() {
            if (!contribucionInvocada) recoverRead(NOMBRE);
            if (contribucion == null) throw invalid("TOOL_EXECUTION_FAILED", "No se pudo completar la sugerencia de contribución.");
        }

        synchronized void recoverCompensation() {
            String name = compensation.allowedTool();
            if (prepared || compensation.blockPreparations() || compensation.recoveryArgs() == null
                    || name == null || !RECOVERABLE_COMPENSATION.contains(name)) return;
            execute(callback(name), NO_ARGUMENTS, new ToolContext(Map.of()));
        }

        private ToolCallback callback(String name) {
            return Arrays.stream(AgentToolRegistry.this.callbacks)
                    .filter(candidate -> candidate.getToolDefinition().name().equals(name))
                    .findFirst()
                    .orElseThrow(() -> invalid("TOOL_UNAVAILABLE", "La herramienta solicitada no está disponible."));
        }

        private String execute(ToolCallback delegate, String input, ToolContext context) {
            return execute(delegate, input, context, false);
        }

        private synchronized String execute(ToolCallback delegate, String input, ToolContext context, boolean proveedor) {
            if (++calls > 8) throw invalid("TOOL_LIMIT", "El agente alcanzó el límite de herramientas por mensaje.");
            String name = delegate.getToolDefinition().name();
            boolean prepara = PREPARATIONS.contains(name);
            boolean coincide = name.equals(compensation.allowedTool());
            boolean preparaCompensacion = RECOVERABLE_COMPENSATION.contains(name);
            boolean argumentosVerificados = coincide && compensation.recoveryArgs() != null;
            // Provider and fallback share the same verified arguments and receipt.
            if (preparaCompensacion && argumentosVerificados) {
                input = mapper.writeValueAsString(compensation.recoveryArgs());
            }
            boolean pdf = DESCARGA_HISTORIAL_PDF.equals(name);
            boolean sugiereContribucion = NOMBRE.equals(name);
            boolean noArguments = pdf || sugiereContribucion || AgentReportIntent.isReportTool(name);
            Object arguments = sugiereContribucion ? null : mapper.readValue(input, Object.class);
            IntencionAclarable.Borrador seleccion = null;
            if (proveedor && RUTAS_Y_VIAJES_ACLARABLES.contains(name)) {
                seleccion = IntencionAclarable.desdeTool(name,
                        arguments instanceof Map<?, ?> parametros ? parametros : Map.of(), mensajeUsuario, rolUsuario, reloj);
                if (seleccion == null) {
                    solicitudBloqueada = "Indica explícitamente qué operación deseas solicitar y los datos que correspondan.";
                    return responderSinEjecutar(name + NO_ARGUMENTS, solicitudBloqueada);
                }
                arguments = seleccion.argumentos();
                input = mapper.writeValueAsString(arguments);
            }
            // Server-derived tool arguments have one receipt even if the model adds fields.
            String key = name + (noArguments ? NO_ARGUMENTS
                    : mapper.writeValueAsString(canonical(arguments)));
            if (receipts.containsKey(key)) return receipts.get(key);
            if (prepara && prepared)
                throw invalid("ONE_OPERATION_PER_TURN", "Solo se puede preparar una operación por mensaje. Revisa la confirmación pendiente.");
            if (prepara && (compensation.blockPreparations()
                    || (compensation.allowedTool() != null && !coincide)
                    || (preparaCompensacion && !argumentosVerificados))) {
                return responderSinEjecutar(key, "Usa únicamente la acción explícita y los datos indicados por el usuario.");
            }
            if (pdf && (historialPdf == null || historialPdf.filtros() == null)) {
                String detalle = historialPdf == null ? "La descarga requiere una solicitud explícita del usuario."
                        : historialPdf.aclaracion();
                return responderSinEjecutar(key, detalle);
            }
            if (pdf) context = new ToolContext(Map.of(CONTEXTO_FILTROS_PDF, historialPdf.filtros()));
            if (sugiereContribucion) {
                if (contribucionInvocada)
                    throw invalid("TOOL_EXECUTION_FAILED", "No se pudo completar la sugerencia de contribución.");
                contribucionInvocada = true;
                context = new ToolContext(Map.of(CONTEXTO_FILTROS, filtrosContribucion));
            }
            var borrador = seleccion == null ? rutaOViaje : seleccion;
            SeleccionRutaViaje seleccionRuta = null;
            if ("crearViaje".equals(name) && borrador != null && borrador.rutaViaje() != null) {
                seleccionRuta = new SeleccionRutaViaje(borrador.rutaViaje());
                context = new ToolContext(Map.of(SeleccionRutaViaje.CONTEXTO, seleccionRuta));
            }
            if (prepara) prepared = true;
            String output = llamar(delegate, noArguments ? NO_ARGUMENTS : input, context, prepara);
            if (AgentConversationStore.containsSecrets(output))
                throw invalid("SENSITIVE_TOOL_RESULT", "No se enviaron al proveedor datos que parecen contener credenciales.");
            JsonNode data = mapper.readTree(output);
            capture(name, data);
            if (seleccionRuta != null) {
                rutaOViaje = borrador.conRutaViaje(seleccionRuta.estado());
                if (data instanceof ObjectNode objeto && objeto.has("aclaracion")) {
                    objeto.remove("aclaracion");
                    output = mapper.writeValueAsString(objeto);
                }
            } else if (seleccion != null) {
                rutaOViaje = seleccion;
            }
            receipts.put(key, output);
            return output;
        }

        private String llamar(ToolCallback delegate, String input, ToolContext context, boolean prepara) {
            var intento = prepara ? IntentoPreparacion.observar() : null;
            try {
                return delegate.call(input, context);
            } catch (RuntimeException failure) {
                // Spring AI's default processor returns ToolExecutionException.message to the model.
                // Unwrap permitted public errors; never let database/SDK exception details reach it.
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof com.uniride.exceptions.NoPermitidoException denied) throw denied;
                    if (cause instanceof com.uniride.exceptions.NoAutorizadoException denied) throw denied;
                    if (cause instanceof com.uniride.exceptions.CamposInvalidosException invalid) throw invalid;
                    if (cause instanceof com.uniride.exceptions.BusinessException business) throw business;
                    if (cause instanceof com.uniride.exceptions.ResourceNotFoundException missing) throw missing;
                    if (cause instanceof AgentProviderException controlled) throw controlled;
                }
                throw invalid("TOOL_EXECUTION_FAILED", "No se pudo completar la herramienta. No se repitió ninguna acción.");
            } finally {
                if (intento != null) {
                    persistenciaIntentada = intento.iniciada();
                    intento.close();
                }
            }
        }

        private String responderSinEjecutar(String key, String mensaje) {
            String output = mapper.writeValueAsString(Map.of("mensaje", mensaje));
            receipts.put(key, output);
            return output;
        }

        private void capture(String name, JsonNode data) {
            JsonNode items = data.isArray() ? data : data.path("items");
            switch (name) {
                case NOMBRE -> {
                    var resultado = mapper.treeToValue(data, ContribucionResultado.class);
                    validarAclaracion(name, resultado.aclaracion());
                    contribucion = resultado;
                }
                case "buscarViajes", "consultarMisViajes", "consultarMisSolicitudes" ->
                    items.forEach(item -> viajes.add(mapper.treeToValue(item, ViajeResumen.class)));
                case "consultarMisRutas" ->
                    items.forEach(item -> rutas.add(mapper.treeToValue(item, RutaResumen.class)));
                case OWN_METHODS ->
                    items.forEach(item -> metodos.add(mapper.treeToValue(item, MetodoCompensacionResumen.class)));
                default -> {
                    if (data.has("estado") && data.has("detalle")) {
                        var resultado = mapper.treeToValue(data, OperacionResumen.class);
                        validarAclaracion(name, resultado.aclaracion());
                        camposOperacion = resultado.aclaracion();
                        operation = resultado.conAclaracion(null);
                    }
                }
            }
            if (data instanceof ObjectNode objeto && objeto.has("aclaracion")) {
                data = objeto.deepCopy();
                ((ObjectNode) data).remove("aclaracion");
            }
            results.add(new ResultadoAgente(name, data));
        }

        private void validarAclaracion(String herramienta, AclaracionAgente datos) {
            if (datos != null && (datos.id() != null || !herramienta.equals(datos.operacion()))) {
                throw invalid("INVALID_TOOL_RESPONSE", "La herramienta devolvió una aclaración inválida.");
            }
        }

        public boolean hasResults() { return !results.isEmpty(); }

        boolean hasDirectResult() {
            return operation != null || solicitudBloqueada != null
                    || results.stream().anyMatch(result -> OWN_METHODS.equals(result.tipo()));
        }

        ContribucionResultado contribucion() { return contribucion; }

        public ChatResponse response(String text) {
            return response(text, null);
        }

        public ChatResponse response(String text, AdvertenciaAgente warning) {
            return new ChatResponse(message(text, warning), List.copyOf(viajes), List.copyOf(rutas),
                    List.copyOf(metodos), operation, List.copyOf(results), warning).conAclaracion(aclaracion());
        }

        private AclaracionAgente aclaracion() {
            if (operation != null && (operation.confirmacion() != null
                    || operation.estado() == EstadoOperacion.CONFIRMACION_REQUERIDA)) return null;
            if (contribucion != null && contribucion.aclaracion() != null) return contribucion.aclaracion();
            if (operation != null && camposOperacion != null
                    && (operation.estado() == EstadoOperacion.DATOS_FALTANTES || operation.estado() == EstadoOperacion.AMBIGUO)) {
                var dato = camposOperacion;
                if (coordinacion != null && dato.operacion().equals(coordinacion.pedido().accion().herramienta())) {
                    return dato;
                }
                if (rutaOViaje != null && rutaOViaje.accion().herramienta.equals(dato.operacion())) {
                    var esquema = operation.estado() == EstadoOperacion.DATOS_FALTANTES ? rutaOViaje.completar(dato) : dato;
                    if (esquema.campos().size() == 1) {
                        var tipo = esquema.campos().getFirst().tipo();
                        if (tipo == TipoCampoAclaracion.TEXT || tipo == TipoCampoAclaracion.TEXTAREA)
                            return esquema.conRespuestaBreve("valor");
                    }
                    return esquema;
                }
                if (dato.operacion().equals(compensation.allowedTool()) && compensation.recoveryArgs() != null) return dato;
            }
            return historialPdf == null ? null : historialPdf.aclaracionEstructurada();
        }

        private String message(String text, AdvertenciaAgente warning) {
            if (contribucion != null) return contribucion.mensaje();
            if (coordinacion != null && coordinacion.mensaje() != null) return coordinacion.mensaje();
            // The operation receipt or controlled warning takes precedence over model text.
            String primary = operation != null ? operation.detalle()
                    : warning != null && !text.isBlank() ? text
                    : compensation.message() != null ? compensation.message() : solicitudBloqueada;
            if (operation != null && operation.estado() == EstadoOperacion.CONFIRMACION_REQUERIDA
                    && operation.confirmacion() != null && operation.confirmacion().estado() == EstadoConfirmacion.PENDIENTE) {
                primary += " Puedes confirmar desde la aplicación o escribir «sí» o «confirmo» en este chat. "
                        + "Para cancelar la propuesta, escribe «no» o «cancelar».";
            }
            List<String> reports = results.stream()
                    .map(result -> AgentReportSummary.describe(result, mapper))
                    .filter(Objects::nonNull)
                    .toList();
            List<String> personal = results.stream()
                    .map(result -> AgentPersonalSummary.describe(result, mapper))
                    .filter(Objects::nonNull)
                    .toList();
            String download = results.stream().filter(result -> DESCARGA_HISTORIAL_PDF.equals(result.tipo()))
                    .map(result -> mapper.treeToValue(result.datos(), DescargaHistorialPdfRespuesta.class).mensaje())
                    .findFirst().orElse(historialPdf == null ? null : historialPdf.aclaracion());
            if (reports.isEmpty() && personal.isEmpty() && download == null) {
                return primary != null ? primary : hasResults() ? querySummary() : clarification(text);
            }
            List<String> sections = new ArrayList<>();
            if (primary != null) sections.add(primary);
            if (results.stream().anyMatch(result -> !"consultarMisHorarios".equals(result.tipo())
                    && (result.datos().has("hayMas") || result.datos().isArray()))) {
                sections.add(querySummary());
            }
            if (!personal.isEmpty()) {
                sections.add(String.join(" ", personal));
            }
            if (!reports.isEmpty()) {
                sections.add(String.join(" ", reports) + (download == null
                        ? " Los reportes consultan todo tu historial y no admiten filtros por fechas."
                        : " Las estadísticas de viajes consultan todo tu historial y no admiten filtros por fechas."));
            }
            if (download != null) sections.add(download);
            return String.join(" ", sections);
        }

        private String querySummary() {
            int count = viajes.size() + rutas.size() + metodos.size();
            boolean more = results.stream().anyMatch(r -> r.datos().path("hayMas").asBoolean(false));
            return (count == 0 ? "No se encontraron resultados en las páginas consultadas."
                    : count == 1 ? "Se encontró 1 resultado en las páginas consultadas."
                    : "Se encontraron " + count + " resultados en las páginas consultadas.")
                    + (more ? " Hay más resultados; puedes pedir la siguiente página." : "");
        }

        private String clarification(String text) {
            // The model selects a topic, never supplies an assertion of business success.
            String normalized = text.toLowerCase(Locale.ROOT);
            if (normalized.contains("¿") || normalized.endsWith("?")) {
                if ((normalized.contains("método") || normalized.contains("metodo"))
                        && (normalized.contains("cambiar") || normalized.contains("modificar")))
                    return "¿Qué quieres cambiar en tu método de compensación? Indica el nuevo tipo o la nueva descripción.";
                if (normalized.contains("fecha") || normalized.contains("hora"))
                    return "¿Qué fecha y hora deseas para el viaje?";
                if (solicitudViaje && normalized.contains("compensa"))
                    return "¿Prefieres compensar con DINERO, GASOLINA o FAVOR?";
                if (normalized.contains("capacidad") || normalized.contains("referencia"))
                    return "¿Cuál es el origen, la capacidad máxima y los puntos de referencia de la ruta?";
                if (normalized.contains("ruta") || normalized.contains("origen") || normalized.contains("destino"))
                    return "¿Cuál es el origen y, si corresponde, el destino de la ruta?";
            }
            return "Puedo consultar rutas, viajes, solicitudes, métodos de compensación y estadísticas históricas, "
                    + "o preparar una operación para tu confirmación. ¿Qué deseas hacer?";
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(key.toString(), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(AgentToolRegistry::canonical).toList();
        return value;
    }

    static AgentProviderException invalid(String code, String message) {
        return new AgentProviderException(org.springframework.http.HttpStatus.BAD_GATEWAY, code, message);
    }
}
