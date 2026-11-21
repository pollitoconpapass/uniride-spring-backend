package com.uniride.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import com.uniride.agent.dto.*;
import com.uniride.agent.tools.ReportesAgentTools;
import com.uniride.dto.responses.AnalisisSemanalRespuesta;
import com.uniride.dto.responses.DiaCantidadRespuesta;
import com.uniride.dto.responses.EstadisticasRespuesta;
import com.uniride.dto.responses.RankingRutasRespuesta;
import com.uniride.dto.responses.RutaFrecuenteRespuesta;
import com.uniride.entities.Usuario;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.Rol;
import com.uniride.enums.TipoCompensacion;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.services.EstadisticasService;
import com.uniride.services.UsuarioService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exercises the real Spring AI/OpenAI transport against loopback only; no database or real API key. */
class GroqProviderIntegrationTest {
    private static final String JSON_TOOL_ERROR = "Tool call validation failed: attempted to call tool 'json' which was not in request.tools";
    private final ObjectMapper mapper = new ObjectMapper();
    private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final SimulatedTools simulatedTools = new SimulatedTools();
    private final UsuarioService users = mock(UsuarioService.class);
    private final EstadisticasService reports = mock(EstadisticasService.class);
    private HttpServer server;
    private ExecutorService executor;
    private AgentService service;

    @BeforeEach
    void startLocalProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                requests.add(mapper.readTree(exchange.getRequestBody().readAllBytes()));
                Reply reply = replies.poll();
                if (reply == null) reply = new Reply(500, error("Unexpected extra request"), 0);
                if (reply.delayMillis() > 0) Thread.sleep(reply.delayMillis());
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        when(users.usuarioActual()).thenReturn(Usuario.builder().id(7L).rolPrincipal(Rol.CONDUCTOR).build());
        service = service(Duration.ofSeconds(3));
    }

    @AfterEach
    void stopLocalProvider() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    private AgentService service(Duration timeout) {
        var options = OpenAiChatOptions.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/openai/v1")
                .apiKey("simulated-provider-test-only")
                .model("openai/gpt-oss-120b")
                .temperature(0.1)
                .maxRetries(0)
                .timeout(timeout)
                .parallelToolCalls(false)
                .replayReasoningContent(false)
                .build();
        var model = OpenAiChatModel.builder().options(options).build();
        ChatClient client = new AgentConfig().chatClient(ChatClient.builder(model));
        return new AgentService(client, users,
                Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneId.of("America/Lima")),
                new AgentToolRegistry(mapper, ToolCallbacks.from(simulatedTools, new ReportesAgentTools(reports, users))));
    }

    @Test
    void rejectsPhantomJsonToolWithoutRetryOrChangingRegisteredTools() {
        enqueue(400, error(JSON_TOOL_ERROR));

        assertThatThrownBy(() -> service.chat("owner", "Consulta mis rutas"))
                .isInstanceOfSatisfying(AgentProviderException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(failure.code()).isEqualTo("PROVIDER_REJECTED");
                    assertThat(failure.getMessage()).doesNotContain("request.tools", "BadRequestException");
                });

        assertThat(requests).hasSize(1);
        assertThat(simulatedTools.executions).hasValue(0);
        JsonNode request = requests.getFirst();
        assertThat(request.path("model").asText()).isEqualTo("openai/gpt-oss-120b");
        assertThat(request.has("response_format")).isFalse();
        assertThat(request.path("tools").isArray()).isTrue();
        assertThat(java.util.stream.StreamSupport.stream(request.path("tools").spliterator(), false)
                .map(tool -> tool.path("function").path("name").asText()).toList())
                .contains("consultarMisRutas", "buscarViajes", "consultarMisViajes", "consultarMisMetodosCompensacion",
                        "consultarMisEstadisticas", "consultarMiFrecuenciaViajes", "consultarRankingMisRutas")
                .doesNotContain("json");
        assertThat(request.path("messages").toString())
                .doesNotContain("$schema", "JSON Schema", "Your response should be in JSON format");
        assertThat(request.has("disable_tool_validation")).isFalse();
    }

    @Test
    void keepsAllReportResultsAndSummariesWhenTheProviderInventsStatistics() {
        var resumen = new EstadisticasRespuesta(8, 4, 2, 1);
        var frecuencia = new AnalisisSemanalRespuesta(true, null, List.of(
                new DiaCantidadRespuesta("Lunes", 6), new DiaCantidadRespuesta("Martes", 4),
                new DiaCantidadRespuesta("Miercoles", 0), new DiaCantidadRespuesta("Jueves", 0),
                new DiaCantidadRespuesta("Viernes", 2), new DiaCantidadRespuesta("Sabado", 0),
                new DiaCantidadRespuesta("Domingo", 0)), 1, 3);
        var ranking = new RankingRutasRespuesta(true, null, List.of(
                new RutaFrecuenteRespuesta("Surco", "UPC", 5, 0),
                new RutaFrecuenteRespuesta("Lince", "UPC", 3, 1)));
        when(reports.resumen()).thenReturn(resumen);
        when(reports.analisisSemanal()).thenReturn(frecuencia);
        when(reports.rankingRutas()).thenReturn(ranking);
        enqueue(200, toolCall("consultarMisEstadisticas"));
        enqueue(200, toolCall("consultarMiFrecuenciaViajes"));
        enqueue(200, toolCall("consultarRankingMisRutas"));
        enqueue(200, completion("Inventado: completaste 999 viajes, todos en la última semana.", "stop"));

        ChatResponse response = service.chat(
                "¿Cuántos viajes he hecho, cuál es mi frecuencia de los últimos siete días y qué rutas tienen más penalidades?",
                (UUID) null);

        assertThat(response.conversationId()).isNotNull();
        assertThat(response.resultados()).extracting(ResultadoAgente::tipo)
                .containsExactly("consultarMisEstadisticas", "consultarMiFrecuenciaViajes", "consultarRankingMisRutas");
        assertThat(mapper.treeToValue(response.resultados().get(0).datos(), EstadisticasRespuesta.class)).isEqualTo(resumen);
        assertThat(mapper.treeToValue(response.resultados().get(1).datos(), AnalisisSemanalRespuesta.class)).isEqualTo(frecuencia);
        assertThat(mapper.treeToValue(response.resultados().get(2).datos(), RankingRutasRespuesta.class)).isEqualTo(ranking);
        assertThat(response.message()).contains("12 viajes", "8 como conductor", "4 como pasajero",
                        "2 viajes cancelados", "1 penalidad", "Surco", "Lince", "UPC")
                .containsIgnoringCase("frecuencia histórica")
                .containsIgnoringCase("lunes")
                .containsIgnoringCase("domingo")
                .containsIgnoringCase("filtros por fecha")
                .doesNotContain("Inventado", "999 viajes");
        assertThat(response.operacion()).isNull();
        assertThat(response.advertencia()).isNull();
        assertThat(response.viajes()).isEmpty();
        assertThat(response.rutas()).isEmpty();
        assertThat(response.metodosCompensacion()).isEmpty();
        assertThat(requests).hasSize(4);
        assertThat(simulatedTools.executions).hasValue(0);
        verify(reports).resumen();
        verify(reports).analisisSemanal();
        verify(reports).rankingRutas();
        verifyNoMoreInteractions(reports);

        enqueue(200, toolCall("consultarMisEstadisticas"));
        enqueue(200, completion("Datos consultados.", "stop"));
        ChatResponse followUp = service.chat("¿Y cuántos fueron como pasajero?", response.conversationId());

        assertThat(followUp.conversationId()).isEqualTo(response.conversationId());
        assertThat(followUp.resultados()).extracting(ResultadoAgente::tipo).containsExactly("consultarMisEstadisticas");
        assertThat(followUp.message()).contains("4 como pasajero");
        assertThat(requests).hasSize(6);
    }

    @Test
    void retainsEachBusinessMessageWhenHistoryIsInsufficient() {
        String frecuencia = "Tienes 4 viaje(s) completado(s); necesitas al menos 5 para generar el análisis semanal.";
        String ranking = "Tienes 2 viaje(s) completado(s) como conductor; necesitas al menos 5 para generar el ranking de rutas.";
        when(reports.analisisSemanal()).thenReturn(new AnalisisSemanalRespuesta(false, frecuencia, List.of(), 2, 1));
        when(reports.rankingRutas()).thenReturn(new RankingRutasRespuesta(false, ranking, List.of()));
        enqueue(200, toolCall("consultarMiFrecuenciaViajes"));
        enqueue(200, toolCall("consultarRankingMisRutas"));
        enqueue(200, completion("Inventado: hiciste 999 viajes el lunes.", "stop"));

        ChatResponse response = service.chat("Dame mi frecuencia y ranking de rutas", (UUID) null);

        assertThat(response.message()).contains(frecuencia, ranking).doesNotContain("Inventado", "999 viajes");
        assertThat(response.resultados()).extracting(ResultadoAgente::tipo)
                .containsExactly("consultarMiFrecuenciaViajes", "consultarRankingMisRutas");
        JsonNode frecuenciaData = response.resultados().getFirst().datos();
        assertThat(frecuenciaData.path("suficientesDatos").asBoolean()).isFalse();
        assertThat(frecuenciaData.path("mensaje").asText()).isEqualTo(frecuencia);
        assertThat(frecuenciaData.path("viajesPorDia").isEmpty()).isTrue();
        assertThat(frecuenciaData.path("canceladosPorMi").asLong()).isEqualTo(2);
        assertThat(frecuenciaData.path("canceladosPorTerceros").asLong()).isEqualTo(1);
        JsonNode rankingData = response.resultados().getLast().datos();
        assertThat(rankingData.path("suficientesDatos").asBoolean()).isFalse();
        assertThat(rankingData.path("mensaje").asText()).isEqualTo(ranking);
        assertThat(rankingData.path("ranking").isEmpty()).isTrue();
        assertThat(response.operacion()).isNull();
    }

    @Test
    void passengerCannotAccessRankingEvenWhenProviderSelectsIt() {
        when(users.usuarioActual()).thenReturn(Usuario.builder().id(7L).rolPrincipal(Rol.PASAJERO).build());
        enqueue(200, toolCall("consultarRankingMisRutas"));

        assertThatThrownBy(() -> service.chat("¿Cuáles son mis rutas más utilizadas?", (UUID) null))
                .isInstanceOf(NoPermitidoException.class)
                .hasMessageContaining("CONDUCTOR");
        assertThat(requests).hasSize(1);
        verifyNoInteractions(reports);
    }

    @Test
    void preservesCapturedReportWhenTheProviderRejectsItsFollowUp() {
        var resumen = new EstadisticasRespuesta(8, 4, 2, 1);
        when(reports.resumen()).thenReturn(resumen);
        enqueue(200, toolCall("consultarMisEstadisticas"));
        enqueue(400, error(JSON_TOOL_ERROR));

        ChatResponse response = service.chat("Consulta mi resumen de viajes", (UUID) null);

        assertThat(response.advertencia().codigo()).isEqualTo("PROVIDER_REJECTED");
        assertThat(response.resultados()).extracting(ResultadoAgente::tipo).containsExactly("consultarMisEstadisticas");
        assertThat(mapper.treeToValue(response.resultados().getFirst().datos(), EstadisticasRespuesta.class)).isEqualTo(resumen);
        assertThat(response.operacion()).isNull();
        assertThat(requests).hasSize(2);
        verify(reports).resumen();
        verifyNoMoreInteractions(reports);
    }

    @ParameterizedTest
    @ValueSource(strings = {"consultarMisRutas", "buscarViajes", "consultarMisViajes", "consultarMisMetodosCompensacion"})
    void keepsStructuredDataFromActualToolsEvenWhenModelInventsJson(String tool) {
        enqueue(200, toolCall(tool));
        enqueue(200, completion("{\"message\":\"Inventado\",\"rutas\":[{\"rutaId\":999}]}", "stop"));

        ChatResponse response = service.chat("owner", "Consulta los datos");

        assertThat(requests).hasSize(2);
        assertThat(simulatedTools.executions).hasValue(1);
        assertThat(response.resultados()).hasSize(1);
        assertThat(response.resultados().getFirst().tipo()).isEqualTo(tool);
        assertThat(response.message()).doesNotContain("Inventado");
        switch (tool) {
            case "consultarMisRutas" -> assertThat(response.rutas()).extracting(RutaResumen::rutaId).containsExactly(13L);
            case "consultarMisMetodosCompensacion" -> assertThat(response.metodosCompensacion()).extracting(MetodoCompensacionResumen::id).containsExactly(17L);
            default -> assertThat(response.viajes()).extracting(ViajeResumen::viajeId).containsExactly(23L);
        }
        assertThat(requests.get(1).path("messages").toString()).contains("\"role\":\"tool\"");
    }

    @Test
    void recoversCompletedResultAfterProviderRejectsJsonWithoutRepeatingAction() {
        enqueue(200, toolCall("registrarMetodoCompensacion"));
        enqueue(400, error(JSON_TOOL_ERROR));

        ChatResponse response = service.chat("owner", "Registra mi método");

        assertThat(requests).hasSize(2);
        assertThat(simulatedTools.executions).hasValue(1);
        assertThat(response.operacion().estado()).isEqualTo(EstadoOperacion.EXITO);
        assertThat(response.operacion().id()).isEqualTo(31L);
        assertThat(response.message()).isEqualTo("Resultado simulado registrado una sola vez.");
    }

    @Test
    void repeatedToolCallInSameTurnUsesReceipt() {
        enqueue(200, toolCall("registrarMetodoCompensacion"));
        enqueue(200, toolCall("registrarMetodoCompensacion"));
        enqueue(200, completion("Terminó la preparación.", "stop"));

        ChatResponse response = service.chat("owner", "Registra mi método");

        assertThat(requests).hasSize(3);
        assertThat(simulatedTools.executions).hasValue(1);
        assertThat(response.resultados()).hasSize(1);
    }

    @Test
    void rejectsUnknownToolReturnedByProviderWithoutExecutingAnything() {
        enqueue(200, toolCall("json"));

        assertThatThrownBy(() -> service.chat("owner", "Consulta mis rutas"))
                .isInstanceOfSatisfying(AgentProviderException.class,
                        failure -> assertThat(failure.status()).isEqualTo(HttpStatus.BAD_GATEWAY));

        assertThat(requests).hasSize(1);
        assertThat(simulatedTools.executions).hasValue(0);
    }

    @ParameterizedTest
    @CsvSource({"429,429,PROVIDER_RATE_LIMIT", "401,503,PROVIDER_UNAVAILABLE", "403,503,PROVIDER_UNAVAILABLE", "503,503,PROVIDER_UNAVAILABLE"})
    void mapsProviderFailuresWithoutRetries(int providerStatus, int expectedStatus, String code) {
        enqueue(providerStatus, error("Provider-only internal detail"));

        assertThatThrownBy(() -> service.chat("owner", "Consulta mis rutas"))
                .isInstanceOfSatisfying(AgentProviderException.class, failure -> {
                    assertThat(failure.status().value()).isEqualTo(expectedStatus);
                    assertThat(failure.code()).isEqualTo(code);
                    assertThat(failure.getMessage()).doesNotContain("Provider-only internal detail");
                });

        assertThat(requests).hasSize(1);
        assertThat(simulatedTools.executions).hasValue(0);
    }

    @Test
    void mapsTimeoutWithoutRetry() {
        service = service(Duration.ofMillis(250));
        replies.add(new Reply(200, completion("Demasiado tarde.", "stop"), 1500));

        assertThatThrownBy(() -> service.chat("owner", "Consulta mis rutas"))
                .isInstanceOfSatisfying(AgentProviderException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                    assertThat(failure.code()).isEqualTo("PROVIDER_TIMEOUT");
                });

        assertThat(requests).hasSize(1);
        assertThat(simulatedTools.executions).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{not-json", "```json\n{}\n```"})
    void rejectsInvalidModelOutput(String text) {
        enqueue(200, completion(text, "stop"));

        assertThatThrownBy(() -> service.chat("owner", "Consulta mis rutas"))
                .isInstanceOfSatisfying(AgentProviderException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(failure.code()).isEqualTo("INVALID_RESPONSE");
                });
        assertThat(requests).hasSize(1);
    }

    @Test
    void rejectsIncompleteModelOutput() {
        enqueue(200, completion("Respuesta cortada", "length"));

        assertThatThrownBy(() -> service.chat("owner", "Consulta mis rutas"))
                .isInstanceOfSatisfying(AgentProviderException.class, failure ->
                        assertThat(failure.code()).isEqualTo("INVALID_RESPONSE"));
        assertThat(requests).hasSize(1);
    }

    @Test
    void asksForMissingDataWithoutInventingStructuredResults() {
        enqueue(200, completion("¿Qué fecha y hora deseas para el viaje?", "stop"));

        ChatResponse response = service.chat("owner", "Crea un viaje en mi ruta de Surco");

        assertThat(response.message()).contains("fecha y hora");
        assertThat(response.resultados()).isEmpty();
        assertThat(response.viajes()).isEmpty();
        assertThat(response.operacion()).isNull();
        assertThat(simulatedTools.executions).hasValue(0);
    }

    @Test
    void toolPermissionFailureStopsBeforeAnotherProviderRequest() {
        enqueue(200, toolCall("fallarPorPermisos"));
        assertThatThrownBy(() -> service.chat("owner", "Consulta no autorizada"))
                .isInstanceOf(com.uniride.exceptions.NoPermitidoException.class);
        assertThat(requests).hasSize(1);
    }

    @Test
    void internalToolFailureNeverBecomesAProviderMessage() {
        enqueue(200, toolCall("fallarConSql"));
        assertThatThrownBy(() -> service.chat("owner", "Consulta los datos"))
                .isInstanceOfSatisfying(AgentProviderException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(failure.code()).isEqualTo("TOOL_EXECUTION_FAILED");
                    assertThat(failure.getMessage()).doesNotContain("SELECT", "jdbc", "internal_table");
                });
        assertThat(requests).hasSize(1);
    }

    @Test
    void credentialMessageIsRejectedBeforeProviderAccess() {
        assertThatThrownBy(() -> service.chat("owner", "Mi contraseña es Fixture123!"))
                .isInstanceOfSatisfying(AgentProviderException.class, failure ->
                        assertThat(failure.code()).isEqualTo("SENSITIVE_MESSAGE"));
        assertThat(requests).isEmpty();
        assertThat(simulatedTools.executions).hasValue(0);
    }

    private void enqueue(int status, String body) { replies.add(new Reply(status, body, 0)); }

    private String error(String message) {
        return mapper.writeValueAsString(Map.of("error", Map.of("message", message,
                "type", "invalid_request_error", "code", "tool_use_failed")));
    }

    private String completion(String text, String finishReason) {
        return envelope(Map.of("role", "assistant", "content", text), finishReason);
    }

    private String toolCall(String tool) {
        return envelope(Map.of("role", "assistant", "content", "", "tool_calls", List.of(
                Map.of("id", "call_simulated", "type", "function", "function", Map.of("name", tool, "arguments", "{}")))), "tool_calls");
    }

    private String envelope(Map<String, Object> message, String finishReason) {
        return mapper.writeValueAsString(Map.of("id", "chatcmpl-simulated", "object", "chat.completion",
                "created", 1795273200L, "model", "openai/gpt-oss-120b", "choices", List.of(
                        Map.of("index", 0, "finish_reason", finishReason, "message", message)),
                "usage", Map.of("prompt_tokens", 15, "completion_tokens", 10, "total_tokens", 25)));
    }

    private record Reply(int status, String body, long delayMillis) { }

    public static final class SimulatedTools {
        final AtomicInteger executions = new AtomicInteger();

        @Tool(description = "Simula una restricción de permisos")
        public String fallarPorPermisos() {
            throw new com.uniride.exceptions.NoPermitidoException("Permiso insuficiente de prueba");
        }

        @Tool(description = "Simula un fallo interno que nunca debe enviarse al proveedor")
        public String fallarConSql() {
            throw new IllegalStateException("SELECT internal_table FROM jdbc:private-database");
        }

        @Tool(description = "Consulta rutas propias de prueba")
        public List<RutaResumen> consultarMisRutas() {
            executions.incrementAndGet();
            return List.of(new RutaResumen(13L, "Surco", "UPC", "Parque", null, 4, EstadoRuta.ACTIVA));
        }

        @Tool(description = "Busca viajes de prueba")
        public List<ViajeResumen> buscarViajes() { return trips(); }

        @Tool(description = "Consulta viajes propios de prueba")
        public List<ViajeResumen> consultarMisViajes() { return trips(); }

        private List<ViajeResumen> trips() {
            executions.incrementAndGet();
            return List.of(new ViajeResumen(23L, 13L, "Surco", "UPC", "Lunes", LocalDate.of(2026, 11, 23),
                    LocalTime.of(8, 0), "Parque", "Ana", 4, null, null));
        }

        @Tool(description = "Consulta métodos propios de prueba")
        public List<MetodoCompensacionResumen> consultarMisMetodosCompensacion() {
            executions.incrementAndGet();
            return List.of(new MetodoCompensacionResumen(17L, TipoCompensacion.GASOLINA, "Compartir combustible", true));
        }

        @Tool(description = "Operación exclusivamente simulada, sin persistencia")
        public OperacionResumen registrarMetodoCompensacion() {
            executions.incrementAndGet();
            return new OperacionResumen(EstadoOperacion.EXITO, 31L, "Resultado simulado registrado una sola vez.");
        }
    }
}
