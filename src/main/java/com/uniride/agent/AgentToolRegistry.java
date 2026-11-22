package com.uniride.agent;

import static com.uniride.agent.tools.ReportesAgentTools.CONTEXTO_FILTROS_PDF;
import static com.uniride.agent.tools.ReportesAgentTools.DESCARGA_HISTORIAL_PDF;

import com.uniride.agent.dto.*;
import com.uniride.agent.tools.*;
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

/** Each turn owns its receipts and budget; nothing is shared between users or threads. */
@Component
public class AgentToolRegistry {
    private static final Set<String> PREPARATIONS = Set.of("crearRuta", "crearViaje", "solicitarUnirse",
            "registrarMetodoCompensacion", "actualizarMetodoCompensacion", "cambiarEstadoMetodoCompensacion",
            "eliminarMetodoCompensacion");
    private static final Set<String> RECOVERABLE_COMPENSATION = Set.of("registrarMetodoCompensacion", "cambiarEstadoMetodoCompensacion");
    private static final String NO_ARGUMENTS = "{}";
    private final ObjectMapper mapper;
    private final ToolCallback[] callbacks;

    @Autowired
    public AgentToolRegistry(ObjectMapper mapper, ViajeAgentTools viajes, RutaAgentTools rutas,
                             MetodoCompensacionAgentTools metodos, ReportesAgentTools reportes) {
        this(mapper, ToolCallbacks.from(viajes, rutas, metodos, reportes));
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
        return new Turn(historialPdf, compensation, solicitudViaje);
    }

    public final class Turn {
        private final HistorialPdfIntent.Solicitud historialPdf;
        private final CompensationIntent.Decision compensation;
        private final boolean solicitudViaje;
        private int calls;
        private final List<ResultadoAgente> results = new ArrayList<>();
        private final Map<String, String> receipts = new HashMap<>();
        private final List<ViajeResumen> viajes = new ArrayList<>();
        private final List<RutaResumen> rutas = new ArrayList<>();
        private final List<MetodoCompensacionResumen> metodos = new ArrayList<>();
        private OperacionResumen operation;
        private boolean prepared;

        private Turn(HistorialPdfIntent.Solicitud historialPdf, CompensationIntent.Decision compensation, boolean solicitudViaje) {
            this.historialPdf = historialPdf;
            this.compensation = compensation;
            this.solicitudViaje = solicitudViaje;
        }

        public ToolCallback[] callbacks() {
            return Arrays.stream(AgentToolRegistry.this.callbacks).map(delegate -> new ToolCallback() {
                public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
                public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
                public String call(String input) { return call(input, new ToolContext(Map.of())); }
                public String call(String input, ToolContext context) {
                    return execute(delegate, input, context);
                }
            }).toArray(ToolCallback[]::new);
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

        synchronized void recoverCompensation() {
            String name = compensation.allowedTool();
            if (prepared || compensation.recoveryArgs() == null || name == null || !RECOVERABLE_COMPENSATION.contains(name)) return;
            execute(callback(name), NO_ARGUMENTS, new ToolContext(Map.of()));
        }

        private ToolCallback callback(String name) {
            return Arrays.stream(AgentToolRegistry.this.callbacks)
                    .filter(candidate -> candidate.getToolDefinition().name().equals(name))
                    .findFirst()
                    .orElseThrow(() -> invalid("TOOL_UNAVAILABLE", "La herramienta solicitada no está disponible."));
        }

        private synchronized String execute(ToolCallback delegate, String input, ToolContext context) {
            if (++calls > 8) throw invalid("TOOL_LIMIT", "El agente alcanzó el límite de herramientas por mensaje.");
            String name = delegate.getToolDefinition().name();
            boolean prepara = PREPARATIONS.contains(name);
            boolean coincide = name.equals(compensation.allowedTool());
            // Provider and fallback share the same verified arguments and receipt.
            if (prepara && coincide && compensation.recoveryArgs() != null) {
                input = mapper.writeValueAsString(compensation.recoveryArgs());
            }
            Object arguments = mapper.readValue(input, Object.class);
            boolean pdf = DESCARGA_HISTORIAL_PDF.equals(name);
            boolean noArguments = pdf || AgentReportIntent.isReportTool(name);
            // Extra model fields cannot change a report's filters or its receipt.
            String key = name + (noArguments ? NO_ARGUMENTS : mapper.writeValueAsString(canonical(arguments)));
            if (receipts.containsKey(key)) return receipts.get(key);
            if (prepara && prepared)
                throw invalid("ONE_OPERATION_PER_TURN", "Solo se puede preparar una operación por mensaje. Revisa la confirmación pendiente.");
            if (prepara && (compensation.blockPreparations()
                    || (!coincide && (compensation.allowedTool() != null || name.endsWith("MetodoCompensacion"))))) {
                return responderSinEjecutar(key, "Usa únicamente la acción explícita y los datos indicados por el usuario.");
            }
            if (pdf && (historialPdf == null || historialPdf.filtros() == null)) {
                String detalle = historialPdf == null ? "La descarga requiere una solicitud explícita del usuario."
                        : historialPdf.aclaracion();
                return responderSinEjecutar(key, detalle);
            }
            if (pdf) context = new ToolContext(Map.of(CONTEXTO_FILTROS_PDF, historialPdf.filtros()));
            String output;
            try {
                output = delegate.call(noArguments ? NO_ARGUMENTS : input, context);
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
            }
            if (AgentConversationStore.containsSecrets(output))
                throw invalid("SENSITIVE_TOOL_RESULT", "No se enviaron al proveedor datos que parecen contener credenciales.");
            JsonNode data = mapper.readTree(output);
            capture(name, data);
            if (prepara) prepared = true;
            receipts.put(key, output);
            return output;
        }

        private String responderSinEjecutar(String key, String mensaje) {
            String output = mapper.writeValueAsString(Map.of("mensaje", mensaje));
            receipts.put(key, output);
            return output;
        }

        private void capture(String name, JsonNode data) {
            results.add(new ResultadoAgente(name, data));
            JsonNode items = data.isArray() ? data : data.path("items");
            switch (name) {
                case "buscarViajes", "consultarMisViajes", "consultarMisSolicitudes" ->
                    items.forEach(item -> viajes.add(mapper.treeToValue(item, ViajeResumen.class)));
                case "consultarMisRutas" ->
                    items.forEach(item -> rutas.add(mapper.treeToValue(item, RutaResumen.class)));
                case "consultarMisMetodosCompensacion" ->
                    items.forEach(item -> metodos.add(mapper.treeToValue(item, MetodoCompensacionResumen.class)));
                default -> {
                    if (data.has("estado") && data.has("detalle"))
                        operation = mapper.treeToValue(data, OperacionResumen.class);
                }
            }
        }

        public boolean hasResults() { return !results.isEmpty(); }

        public ChatResponse response(String text) {
            return response(text, null);
        }

        public ChatResponse response(String text, AdvertenciaAgente warning) {
            return new ChatResponse(message(text, warning), List.copyOf(viajes), List.copyOf(rutas),
                    List.copyOf(metodos), operation, List.copyOf(results), warning);
        }

        private String message(String text, AdvertenciaAgente warning) {
            // The operation receipt or controlled warning takes precedence over model text.
            String primary = operation != null ? operation.detalle() : warning != null ? text : compensation.message();
            List<String> reports = results.stream()
                    .map(result -> AgentReportSummary.describe(result, mapper))
                    .filter(Objects::nonNull)
                    .toList();
            String download = results.stream().filter(result -> DESCARGA_HISTORIAL_PDF.equals(result.tipo()))
                    .map(result -> mapper.treeToValue(result.datos(), DescargaHistorialPdfRespuesta.class).mensaje())
                    .findFirst().orElse(historialPdf == null ? null : historialPdf.aclaracion());
            if (reports.isEmpty() && download == null) {
                return primary != null ? primary : hasResults() ? querySummary() : clarification(text);
            }
            List<String> sections = new ArrayList<>();
            if (primary != null) sections.add(primary);
            if (results.stream().anyMatch(result -> result.datos().has("hayMas") || result.datos().isArray())) {
                sections.add(querySummary());
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
