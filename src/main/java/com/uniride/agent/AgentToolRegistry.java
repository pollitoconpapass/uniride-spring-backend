package com.uniride.agent;

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
    private static final Set<String> PREPARATIONS = Set.of("crearRuta", "crearViaje", "solicitarUnirse", "registrarMetodoCompensacion");
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

    public Turn newTurn() { return new Turn(); }

    public final class Turn {
        private int calls;
        private final List<ResultadoAgente> results = new ArrayList<>();
        private final Map<String, String> receipts = new HashMap<>();
        private final List<ViajeResumen> viajes = new ArrayList<>();
        private final List<RutaResumen> rutas = new ArrayList<>();
        private final List<MetodoCompensacionResumen> metodos = new ArrayList<>();
        private OperacionResumen operation;
        private boolean prepared;

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

        private synchronized String execute(ToolCallback delegate, String input, ToolContext context) {
            if (++calls > 8) throw invalid("TOOL_LIMIT", "El agente alcanzó el límite de herramientas por mensaje.");
            String name = delegate.getToolDefinition().name();
            String key = name + mapper.writeValueAsString(canonical(mapper.readValue(input, Object.class)));
            if (receipts.containsKey(key)) return receipts.get(key);
            if (PREPARATIONS.contains(name) && prepared)
                throw invalid("ONE_OPERATION_PER_TURN", "Solo se puede preparar una operación por mensaje. Revisa la confirmación pendiente.");
            String output;
            try {
                output = delegate.call(input, context);
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
            if (PREPARATIONS.contains(name)) prepared = true;
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
            String primary = operation != null ? operation.detalle() : warning != null ? text : null;
            List<String> reports = results.stream()
                    .map(result -> AgentReportSummary.describe(result, mapper))
                    .filter(Objects::nonNull)
                    .toList();
            if (reports.isEmpty()) {
                return primary != null ? primary : hasResults() ? querySummary() : clarification(text);
            }
            String reportSummary = String.join(" ", reports)
                    + " Los reportes consultan todo tu historial y no admiten filtros por fechas.";
            if (results.stream().anyMatch(result -> result.datos().has("hayMas") || result.datos().isArray())) {
                reportSummary = querySummary() + " " + reportSummary;
            }
            return primary == null ? reportSummary : primary + " " + reportSummary;
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
                if (normalized.contains("fecha") || normalized.contains("hora"))
                    return "¿Qué fecha y hora deseas para el viaje?";
                if (normalized.contains("compensa"))
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
