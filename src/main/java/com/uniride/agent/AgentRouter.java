package com.uniride.agent;

import static com.uniride.agent.support.AgentText.normalizar;
import static com.uniride.agent.tools.pasajero.ContribucionTools.CONTEXTO_FILTROS;
import static com.uniride.agent.tools.pasajero.ContribucionTools.NOMBRE;
import static com.uniride.agent.tools.shared.ReportesTools.CONTEXTO_FILTROS_PDF;
import static com.uniride.agent.tools.shared.ReportesTools.DESCARGA_HISTORIAL_PDF;

import com.uniride.agent.confirmation.EstadoConfirmacion;
import com.uniride.agent.confirmation.IntentoPreparacion;
import com.uniride.agent.conversation.AgentConversationStore;
import com.uniride.agent.conversation.Aclaraciones;
import com.uniride.agent.conversation.CompensationIntent;
import com.uniride.agent.conversation.ConfirmationIntent;
import com.uniride.agent.conversation.ContribucionIntent;
import com.uniride.agent.conversation.CoordinacionConductorIntent;
import com.uniride.agent.conversation.HistorialPdfIntent;
import com.uniride.agent.conversation.IntencionAclarable;
import com.uniride.agent.conversation.SeleccionRutaViaje;
import com.uniride.agent.dto.*;
import com.uniride.agent.support.AgentProviderException;
import com.uniride.agent.support.AgentReportIntent;
import com.uniride.agent.support.AgentText;
import com.uniride.agent.tools.conductor.*;
import com.uniride.agent.tools.pasajero.*;
import com.uniride.agent.tools.shared.*;
import com.uniride.enums.Rol;
import java.time.Clock;
import java.util.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Registers the capabilities once and binds each invocation to its authenticated turn. */
@Component
public class AgentRouter {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(AgentRouter.class);
    private static final String OWN_METHODS = "consultarMisMetodosCompensacion";
    private static final ToolMetadata RETURN_DIRECT = ToolMetadata.builder().returnDirect(true).build();
    private static final String NO_ARGUMENTS = "{}";
    private static final ToolContext EMPTY_CONTEXT = new ToolContext(Map.of());

    enum Scope { CONDUCTOR, PASAJERO, SHARED }
    enum Access { READ, PREPARE }
    enum Context { NONE, ROUTE_OR_TRIP, COORDINATION, COMPENSATION, CONTRIBUTION, PDF, PERSONAL }
    record Capability(Scope scope, Access access, Context context) {
        boolean allows(Rol role) {
            return role == null || scope == Scope.SHARED || scope.name().equals(role.name());
        }
    }

    private static final Map<String, Capability> CATALOG = catalog();
    private final ObjectMapper mapper;
    private final Map<String, ToolCallback> callbacks;

    @Autowired
    public AgentRouter(ObjectMapper mapper, RutaTools rutas, ViajeConductorTools viajesConductor,
                       SolicitudesConductorTools solicitudes, ViajePasajeroTools viajesPasajero,
                       ContribucionTools contribucion, CompensacionTools metodos, PerfilTools perfiles,
                       HorarioTools horarios, ReportesTools reportes) {
        this(mapper, ToolCallbacks.from(rutas, viajesConductor, solicitudes, viajesPasajero,
                contribucion, metodos, perfiles, horarios, reportes));
        if (!callbacks.keySet().equals(CATALOG.keySet())) {
            throw new IllegalStateException("El catálogo del agente debe coincidir con los métodos @Tool registrados.");
        }
    }

    AgentRouter(ObjectMapper mapper, ToolCallback... callbacks) {
        this.mapper = mapper;
        Map<String, ToolCallback> registered = new LinkedHashMap<>();
        for (var callback : callbacks) {
            var name = callback.getToolDefinition().name();
            if (registered.putIfAbsent(name, callback) != null) {
                throw new IllegalArgumentException("Herramienta duplicada: " + name);
            }
        }
        this.callbacks = Collections.unmodifiableMap(registered);
    }

    static Map<String, Capability> capabilities() {
        return CATALOG;
    }

    private static Map<String, Capability> catalog() {
        Map<String, Capability> catalog = new LinkedHashMap<>();
        register(catalog, Scope.CONDUCTOR, Access.READ, Context.NONE,
                "consultarMisRutas", "consultarMisViajes", "consultarRankingMisRutas");
        for (var action : IntencionAclarable.Accion.values()) {
            register(catalog, Scope.valueOf(action.rol().name()), Access.PREPARE, Context.ROUTE_OR_TRIP,
                    action.herramienta());
        }
        for (var action : CoordinacionConductorIntent.Accion.values()) {
            register(catalog, Scope.CONDUCTOR, action.tipo() == null ? Access.READ : Access.PREPARE,
                    Context.COORDINATION, action.herramienta());
        }
        register(catalog, Scope.PASAJERO, Access.READ, Context.NONE, "buscarViajes", "consultarMisSolicitudes");
        register(catalog, Scope.PASAJERO, Access.READ, Context.CONTRIBUTION, NOMBRE);
        register(catalog, Scope.SHARED, Access.READ, Context.NONE, OWN_METHODS, "consultarMiPerfil",
                "consultarMiUsuario", "consultarMisHorarios", "consultarMisEstadisticas", "consultarMiFrecuenciaViajes");
        register(catalog, Scope.SHARED, Access.PREPARE, Context.PERSONAL,
                "editarPerfil", "editarInfoAdicional", "cambiarRol", "registrarCurso", "eliminarCurso");
        register(catalog, Scope.SHARED, Access.PREPARE, Context.COMPENSATION, "registrarMetodoCompensacion",
                "actualizarMetodoCompensacion", "cambiarEstadoMetodoCompensacion", "eliminarMetodoCompensacion");
        register(catalog, Scope.SHARED, Access.READ, Context.PDF, DESCARGA_HISTORIAL_PDF);
        return Collections.unmodifiableMap(catalog);
    }

    private static void register(Map<String, Capability> catalog, Scope scope, Access access,
                                 Context context, String... names) {
        for (String name : names) {
            if (catalog.putIfAbsent(name, new Capability(scope, access, context)) != null) {
                throw new IllegalStateException("Herramienta duplicada: " + name);
            }
        }
    }

    private static Capability capability(String name) {
        return CATALOG.getOrDefault(name, new Capability(Scope.SHARED, Access.READ, Context.NONE));
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
        private HistorialPdfIntent.Solicitud historialPdf;
        private CompensationIntent.Decision compensation;
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
        private CoordinacionConductorIntent.Sesion coordinacionAnterior;
        private ContribucionIntent.Borrador contribucionAnterior;
        private ContribucionIntent.Solicitud solicitudContribucion;
        private AclaracionAgente aclaracionActiva;
        private boolean aclaracionContextual;
        private AgentConversationStore.Lecturas lecturasAnteriores = new AgentConversationStore.Lecturas(List.of(), List.of(), List.of());
        private List<ViajeResumen> viajesEncontrados = List.of();
        private List<ViajeResumen> solicitudesEncontradas = List.of();
        private AgentConversationStore.CambioPersonal personal;

        private Turn(HistorialPdfIntent.Solicitud historialPdf, CompensationIntent.Decision compensation,
                     boolean solicitudViaje, ContribucionFiltros filtrosContribucion) {
            this.historialPdf = historialPdf;
            this.compensation = compensation;
            this.solicitudViaje = solicitudViaje;
            this.filtrosContribucion = filtrosContribucion;
        }

        ToolCallback[] callbacks() {
            return callbacks(null, false);
        }

        void contextoAclaracion(String mensaje, Rol rol, Clock clock, AclaracionAgente activa,
                                CoordinacionConductorIntent.Sesion coordinacionAnterior,
                                ContribucionIntent.Borrador contribucionAnterior) {
            this.mensajeUsuario = mensaje;
            this.rolUsuario = rol;
            this.reloj = clock;
            this.aclaracionActiva = activa;
            this.coordinacionAnterior = coordinacionAnterior;
            this.contribucionAnterior = contribucionAnterior;
        }

        void referenciasDeBusqueda(AgentConversationStore.Lecturas anteriores) { lecturasAnteriores = anteriores; }
        AgentConversationStore.Lecturas lecturas() {
            return new AgentConversationStore.Lecturas(viajesEncontrados, rutas, solicitudesEncontradas);
        }

        boolean tieneHerramienta(String nombre) {
            return callbacks.containsKey(nombre) && capability(nombre).allows(rolUsuario);
        }

        void procesar(IntencionAclarable.Borrador borrador) {
            rutaOViaje = borrador.vincularLecturas(mensajeUsuario, lecturasAnteriores);
            String nombre = borrador.accion().herramienta();
            execute(callback(nombre), mapper.writeValueAsString(rutaOViaje.argumentos()), EMPTY_CONTEXT);
        }

        void procesar(AgentConversationStore.CambioPersonal cambio) {
            personal = cambio;
            execute(callback(cambio.herramienta()), mapper.writeValueAsString(argumentosCambio()), EMPTY_CONTEXT);
        }

        void procesar(CoordinacionConductorIntent.Pedido pedido) {
            coordinacion = new CoordinacionConductorIntent.Turno(pedido);
            if (pedido.problema() != null) {
                solicitudBloqueada = pedido.problema();
                return;
            }
            execute(callback(pedido.accion().herramienta()), NO_ARGUMENTS, EMPTY_CONTEXT);
        }

        CoordinacionConductorIntent.Sesion coordinacion() { return coordinacion == null ? null : coordinacion.sesion(); }
        HistorialPdfIntent.Solicitud historial() { return historialPdf; }
        CompensationIntent.Decision compensacion() { return compensation; }
        OperacionResumen operacion() { return operation; }
        AgentConversationStore.CambioPersonal cambioPersonal() {
            return operation != null && (operation.estado() == EstadoOperacion.DATOS_FALTANTES
                    || operation.estado() == EstadoOperacion.AMBIGUO) ? personal : null;
        }
        ContribucionIntent.Borrador siguienteContribucion() {
            if (solicitudContribucion == null || solicitudContribucion.filtros() == null) return null;
            if (contribucion != null) return solicitudContribucion.siguiente(contribucion);
            return new ContribucionIntent.Borrador(solicitudContribucion.filtros(),
                    ContribucionResultado.Estado.FALTAN_FILTROS);
        }

        IntencionAclarable.Borrador rutaOViaje() { return rutaOViaje; }

        boolean propuestaPendiente() {
            return operation != null && operation.estado() == EstadoOperacion.CONFIRMACION_REQUERIDA
                    && operation.confirmacion() != null && operation.confirmacion().estado() == EstadoConfirmacion.PENDIENTE;
        }

        boolean tieneAclaracion() { return aclaracion() != null; }

        boolean preparacionIncierta() { return persistenciaIntentada && !propuestaPendiente(); }

        ToolCallback[] callbacks(Rol role, boolean terminalResults) {
            return callbacks.entrySet().stream()
                    .filter(entry -> available(entry.getKey(), role))
                    .sorted(Comparator.comparing(entry -> aclaracionActiva == null
                            || !entry.getKey().equals(aclaracionActiva.operacion())))
                    .map(entry -> new ToolCallback() {
                public ToolDefinition getToolDefinition() { return entry.getValue().getToolDefinition(); }
                public ToolMetadata getToolMetadata() {
                    var name = entry.getKey();
                    var info = capability(name);
                    return terminalResults && (info.access() == Access.PREPARE || OWN_METHODS.equals(name)
                            || info.context() == Context.CONTRIBUTION || info.context() == Context.COORDINATION)
                            ? RETURN_DIRECT : entry.getValue().getToolMetadata();
                }
                public String call(String input) { return call(input, EMPTY_CONTEXT); }
                public String call(String input, ToolContext ignored) {
                    return execute(entry.getValue(), input, EMPTY_CONTEXT, role != null);
                }
            }).toArray(ToolCallback[]::new);
        }

        void completarSolicitudSinTool() {
            if (IntencionAclarable.esPeticionDeCupo(mensajeUsuario) && tieneHerramienta("solicitarUnirse")) {
                var borrador = IntencionAclarable.desdeTool("solicitarUnirse", Map.of(), mensajeUsuario, rolUsuario, reloj);
                if (borrador != null) procesar(borrador);
                return;
            }
            var candidatas = callbacks.keySet().stream().filter(this::tieneHerramienta)
                    .filter(nombre -> capability(nombre).context() == Context.PERSONAL)
                    .map(nombre -> Aclaraciones.cambioDesdeTool(nombre, Map.of(), mensajeUsuario))
                    .filter(Objects::nonNull).toList();
            if (candidatas.size() == 1) procesar(candidatas.getFirst());
        }

        private boolean available(String name, Rol role) {
            var info = capability(name);
            if (!info.allows(role)) return false;
            if (filtrosContribucion != null) return Context.CONTRIBUTION == info.context();
            if (role == null) return true;
            return switch (info.context()) {
                case COMPENSATION -> !compensation.blockPreparations() && !solicitudViaje
                        && CompensationIntent.contextoGestion(mensajeUsuario);
                case CONTRIBUTION -> ContribucionIntent.contextoConsulta(mensajeUsuario);
                case PDF -> historialPdf != null || HistorialPdfIntent.contextoDescarga(mensajeUsuario);
                default -> true;
            };
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
                    || name == null || capability(name).context() != Context.COMPENSATION) return;
            execute(callback(name), NO_ARGUMENTS, new ToolContext(Map.of()));
        }

        private ToolCallback callback(String name) {
            var delegate = callbacks.get(name);
            if (delegate == null) throw invalid("TOOL_UNAVAILABLE", "La herramienta solicitada no está disponible.");
            return delegate;
        }

        private String execute(ToolCallback delegate, String input, ToolContext context) {
            return execute(delegate, input, context, false);
        }

        private synchronized String execute(ToolCallback delegate, String input, ToolContext context, boolean proveedor) {
            if (++calls > 8) throw invalid("TOOL_LIMIT", "El agente alcanzó el límite de herramientas por mensaje.");
            String name = delegate.getToolDefinition().name();
            var info = capability(name);
            LOG.debug("Agente rol={} herramienta={} recorrido={}", rolUsuario, name, proveedor ? "semantico" : "local");
            if (!info.allows(rolUsuario)) {
                throw new com.uniride.exceptions.NoPermitidoException("Esta acción requiere el rol " + info.scope() + ".");
            }
            boolean prepara = info.access() == Access.PREPARE;
            boolean coincide = name.equals(compensation.allowedTool());
            boolean preparaCompensacion = info.context() == Context.COMPENSATION;
            boolean argumentosVerificados = coincide && compensation.recoveryArgs() != null;
            // Provider and fallback share the same verified arguments and receipt.
            if (preparaCompensacion && argumentosVerificados) {
                input = mapper.writeValueAsString(compensation.recoveryArgs());
            }
            boolean contextual = switch (info.context()) {
                case COORDINATION, CONTRIBUTION, PDF -> true;
                default -> false;
            };
            boolean noArguments = contextual || AgentReportIntent.isReportTool(name);
            Object arguments = noArguments ? null : mapper.readValue(input, Object.class);
            IntencionAclarable.Borrador seleccion = null;
            if (proveedor && info.context() == Context.ROUTE_OR_TRIP) {
                seleccion = IntencionAclarable.desdeTool(name,
                        arguments instanceof Map<?, ?> parametros ? parametros : Map.of(), mensajeUsuario, rolUsuario, reloj);
                if (seleccion == null) {
                    solicitudBloqueada = "Indica explícitamente qué operación deseas solicitar y los datos que correspondan.";
                    return responderSinEjecutar(name + NO_ARGUMENTS, solicitudBloqueada);
                }
                seleccion = seleccion.vincularLecturas(mensajeUsuario, lecturasAnteriores);
                arguments = seleccion.argumentos();
                input = mapper.writeValueAsString(arguments);
            }
            // Server-derived tool arguments have one receipt even if the model adds fields.
            String key = name + (noArguments ? NO_ARGUMENTS
                    : mapper.writeValueAsString(canonical(arguments)));
            if (receipts.containsKey(key)) return receipts.get(key);
            if (prepara && prepared)
                throw invalid("ONE_OPERATION_PER_TURN", "Solo se puede preparar una operación por mensaje. Revisa la confirmación pendiente.");
            if (preparaCompensacion && (compensation.blockPreparations() || (compensation.allowedTool() != null && !coincide))
                    || proveedor && prepara && IntencionAclarable.bloqueaPreparacion(mensajeUsuario)) {
                return bloquear(key, "Usa únicamente la acción explícita y los datos indicados por el usuario.");
            }
            if (preparaCompensacion && !argumentosVerificados) {
                var intencion = proveedor ? CompensationIntent.desdeTool(name, mensajeUsuario) : null;
                if (intencion == null) return bloquear(key, "Indica qué quieres hacer con tu método de compensación.");
                compensation = intencion;
                aclaracionContextual = true;
                if (compensation.recoveryArgs() == null) return bloquear(key, compensation.message());
                input = mapper.writeValueAsString(compensation.recoveryArgs());
                key = name + mapper.writeValueAsString(canonical(compensation.recoveryArgs()));
            }
            if (contextual) {
                context = crearContexto(info.context(), name, proveedor);
                if (context == null) return bloquear(key, solicitudBloqueada);
            } else if (proveedor && info.context() == Context.PERSONAL) {
                personal = Aclaraciones.cambioDesdeTool(name, arguments instanceof Map<?, ?> parametros ? parametros : Map.of(),
                        mensajeUsuario);
                if (personal == null) return bloquear(key, "¿Qué cambio quieres preparar en tu perfil u horario?");
                var verificados = argumentosCambio();
                input = mapper.writeValueAsString(verificados);
                key = name + mapper.writeValueAsString(canonical(verificados));
                if (receipts.containsKey(key)) return receipts.get(key);
            }
            if (info.context() == Context.PERSONAL && personal != null
                    && "pendiente".equals(personal.argumentos().get("accionCurso"))) {
                var resultado = new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                        "¿Quieres agregar ese curso a tu horario?")
                        .conAclaracion(Aclaraciones.crear(name, Aclaraciones.Campo.ACCION_CURSO.obligatorio()).conOpcionesRapidas());
                capture(name, mapper.valueToTree(resultado));
                return responderSinEjecutar(key, resultado.detalle());
            }
            var borrador = seleccion == null ? rutaOViaje : seleccion;
            if (borrador != null && name.equals(borrador.accion().herramienta())
                    && (borrador.accion() == IntencionAclarable.Accion.UNIRSE
                    || borrador.accion() == IntencionAclarable.Accion.CANCELAR)) {
                context = new ToolContext(Map.of(IntencionAclarable.CONTEXTO_CUPO, borrador));
            }
            if ("eliminarCurso".equals(name)) {
                context = new ToolContext(Map.of(HorarioTools.CONTEXTO_CURSO, personal == null ? Map.of() : personal.argumentos()));
            }
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
            } else if (seleccion != null) {
                rutaOViaje = seleccion;
            }
            if (data instanceof ObjectNode objeto && objeto.has("aclaracion")) {
                objeto.remove("aclaracion");
                output = mapper.writeValueAsString(objeto);
            }
            receipts.put(key, output);
            return output;
        }

        private ToolContext crearContexto(Context tipo, String name, boolean proveedor) {
            return switch (tipo) {
                case PDF -> {
                    if (historialPdf == null && proveedor) historialPdf = HistorialPdfIntent.desdeTool(mensajeUsuario, reloj);
                    if (historialPdf != null && historialPdf.filtros() != null) {
                        yield new ToolContext(Map.of(CONTEXTO_FILTROS_PDF, historialPdf.filtros()));
                    }
                    solicitudBloqueada = historialPdf == null ? "¿Qué período de tu historial quieres descargar en PDF?"
                            : historialPdf.aclaracion();
                    yield null;
                }
                case CONTRIBUTION -> {
                    if (contribucionInvocada) {
                        throw invalid("TOOL_EXECUTION_FAILED", "No se pudo completar la sugerencia de contribución.");
                    }
                    var filtros = filtrosContribucion;
                    if (filtros == null && proveedor) {
                        solicitudContribucion = ContribucionIntent.desdeTool(mensajeUsuario, contribucionAnterior, reloj);
                        if (solicitudContribucion != null && solicitudContribucion.aclaracion() == null) {
                            filtros = solicitudContribucion.filtros();
                        }
                    }
                    if (filtros == null) {
                        solicitudBloqueada = solicitudContribucion == null ? "¿Para qué viaje quieres estimar el aporte?"
                                : solicitudContribucion.aclaracion();
                        yield null;
                    }
                    contribucionInvocada = true;
                    yield new ToolContext(Map.of(CONTEXTO_FILTROS, filtros));
                }
                case COORDINATION -> {
                    if (proveedor) {
                        var pedido = CoordinacionConductorIntent.desdeTool(name, mensajeUsuario, coordinacionAnterior, reloj);
                        if (pedido.problema() != null) {
                            solicitudBloqueada = pedido.problema();
                            yield null;
                        }
                        coordinacion = new CoordinacionConductorIntent.Turno(pedido);
                    }
                    yield new ToolContext(Map.of(CoordinacionConductorIntent.CONTEXTO, coordinacion));
                }
                default -> throw new IllegalStateException("Contexto de herramienta inválido: " + tipo);
            };
        }

        private Map<String, Object> argumentosCambio() {
            var argumentos = new LinkedHashMap<>(personal.argumentos());
            argumentos.remove("campoPerfil");
            argumentos.remove("curso");
            argumentos.remove("accionCurso");
            return argumentos;
        }

        private String bloquear(String key, String mensaje) {
            LOG.debug("Agente decisión=aclaracion_por_intencion_o_contexto");
            solicitudBloqueada = mensaje;
            return responderSinEjecutar(key, mensaje);
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
                    if (LOG.isDebugEnabled()) LOG.debug("Agente herramienta={} fallo={} origen={}",
                            delegate.getToolDefinition().name(), cause.getClass().getSimpleName(),
                            Arrays.stream(cause.getStackTrace()).filter(frame -> frame.getClassName().startsWith("com.uniride"))
                                    .findFirst().orElse(null));
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
                case "buscarViajes" -> {
                    viajesEncontrados = items.valueStream().map(item -> mapper.treeToValue(item, ViajeResumen.class)).toList();
                    viajes.addAll(viajesEncontrados);
                }
                case "consultarMisSolicitudes" -> {
                    solicitudesEncontradas = items.valueStream().map(item -> mapper.treeToValue(item, ViajeResumen.class)).toList();
                    viajes.addAll(solicitudesEncontradas);
                }
                case "consultarMisViajes" ->
                    items.forEach(item -> viajes.add(mapper.treeToValue(item, ViajeResumen.class)));
                case "consultarMisRutas" ->
                    items.forEach(item -> rutas.add(mapper.treeToValue(item, RutaResumen.class)));
                case OWN_METHODS ->
                    items.forEach(item -> metodos.add(mapper.treeToValue(item, MetodoCompensacionResumen.class)));
                default -> {
                    if (data.has("estado") && data.has("detalle")) {
                        var resultado = mapper.treeToValue(data, OperacionResumen.class);
                        LOG.debug("Agente herramienta={} estado={} aclaracion={}", name, resultado.estado(),
                                resultado.aclaracion() != null);
                        validarAclaracion(name, resultado.aclaracion());
                        if (!propuestaPendiente()) {
                            camposOperacion = resultado.aclaracion();
                            operation = resultado.conAclaracion(null);
                        }
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
            return operation != null || solicitudBloqueada != null || coordinacion != null || contribucion != null
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
            if (solicitudContribucion != null && solicitudContribucion.aclaracionEstructurada() != null)
                return solicitudContribucion.aclaracionEstructurada();
            if (operation != null && camposOperacion != null
                    && (operation.estado() == EstadoOperacion.DATOS_FALTANTES || operation.estado() == EstadoOperacion.AMBIGUO)) {
                var dato = camposOperacion;
                if (coordinacion != null && dato.operacion().equals(coordinacion.pedido().accion().herramienta())) {
                    return dato;
                }
                if (rutaOViaje != null && rutaOViaje.accion().herramienta().equals(dato.operacion())) {
                    var esquema = operation.estado() == EstadoOperacion.DATOS_FALTANTES ? rutaOViaje.completar(dato) : dato;
                    if (esquema.campos().size() == 1) {
                        var tipo = esquema.campos().getFirst().tipo();
                        if (tipo == TipoCampoAclaracion.TEXT || tipo == TipoCampoAclaracion.TEXTAREA)
                            return esquema.conRespuestaBreve("valor");
                    }
                    return esquema;
                }
                if (dato.operacion().equals(compensation.allowedTool()) && compensation.recoveryArgs() != null) return dato;
                if (personal != null && dato.operacion().equals(personal.herramienta())) {
                    return Aclaraciones.precisarCambio(dato, personal);
                }
            }
            if (aclaracionContextual) return compensation.aclaracion();
            return historialPdf == null ? null : historialPdf.aclaracionEstructurada();
        }

        private String message(String text, AdvertenciaAgente warning) {
            if (contribucion != null) return contribucion.mensaje();
            // The operation receipt or controlled warning takes precedence over model text.
            String primary = operation != null ? operation.detalle()
                    : warning != null && !text.isBlank() ? text
                    : coordinacion != null && coordinacion.mensaje() != null ? coordinacion.mensaje()
                    : compensation.message() != null ? compensation.message() : solicitudBloqueada;
            if (operation != null && operation.estado() == EstadoOperacion.CONFIRMACION_REQUERIDA
                    && operation.confirmacion() != null && operation.confirmacion().estado() == EstadoConfirmacion.PENDIENTE) {
                primary += " Puedes confirmar desde la aplicación o escribir «sí» o «confirmo» en este chat. "
                        + "Para cancelar la propuesta, escribe «no» o «cancelar».";
            }
            List<String> reports = results.stream().map(result -> AgentText.report(result, mapper))
                    .filter(Objects::nonNull).toList();
            List<String> personal = results.stream()
                    .map(result -> AgentText.personal(result, mapper))
                    .filter(Objects::nonNull)
                    .toList();
            String download = results.stream().filter(result -> DESCARGA_HISTORIAL_PDF.equals(result.tipo()))
                    .map(result -> mapper.treeToValue(result.datos(), DescargaHistorialPdfRespuesta.class).mensaje())
                    .findFirst().orElse(historialPdf == null ? null : historialPdf.aclaracion());
            if (reports.isEmpty() && personal.isEmpty() && download == null) {
                return primary != null ? primary : hasResults() ? querySummary() : textoSinResultados(text);
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

        private String textoSinResultados(String text) {
            if (solicitudViaje && normalizar(text).contains("compensa") && text.endsWith("?"))
                return "¿Prefieres compensar con DINERO, GASOLINA o FAVOR?";
            return AgentText.withoutResults(mensajeUsuario, text);
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(key.toString(), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(AgentRouter::canonical).toList();
        return value;
    }

    static AgentProviderException invalid(String code, String message) {
        return new AgentProviderException(org.springframework.http.HttpStatus.BAD_GATEWAY, code, message);
    }
}
