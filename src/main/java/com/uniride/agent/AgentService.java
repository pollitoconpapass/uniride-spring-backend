package com.uniride.agent;

import com.uniride.agent.dto.ChatResponse;
import com.uniride.entities.Usuario;
import com.uniride.services.UsuarioService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.springframework.ai.chat.client.ChatClient;
import com.openai.errors.OpenAIServiceException;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.exceptions.NoAutorizadoException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AgentService {

    private static final DateTimeFormatter FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm");

    private final ChatClient chatClient;
    private final UsuarioService usuarioService;
    private final Clock clock;
    private final AgentToolRegistry tools;
    private final AgentConversationStore conversations;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentService(ChatClient chatClient, UsuarioService usuarioService, Clock clock,
                        AgentToolRegistry tools, AgentConversationStore conversations) {
        this.chatClient = chatClient; this.usuarioService = usuarioService; this.clock = clock;
        this.tools = tools; this.conversations = conversations;
    }

    public AgentService(ChatClient chatClient, UsuarioService usuarioService, Clock clock, AgentToolRegistry tools) {
        this(chatClient, usuarioService, clock, tools, new AgentConversationStore(clock));
    }

    /** Compatibility for internal callers. Identity always comes from SecurityContext via UsuarioService. */
    public ChatResponse chat(String username, String message) {
        return chat(message, (java.util.UUID) null);
    }

    public ChatResponse chat(String message, java.util.UUID conversationId) {
        AgentConversationStore.validateMessage(message);
        Usuario usuario = usuarioService.usuarioActual();
        try (var lease = conversations.open(usuario.getId(), usuario.getRolPrincipal(), conversationId)) {
            ChatResponse response = execute(usuario, message, lease.history()).withConversationId(lease.id());
            lease.complete(message, response.message());
            return response;
        }
    }

    private ChatResponse execute(Usuario usuario, String message,
                                 java.util.List<org.springframework.ai.chat.messages.Message> history) {
        String contexto = "Contexto: hoy es " + LocalDate.now(clock) + " y son las "
                + LocalTime.now(clock).format(FORMATO_HORA)
                + " (America/Lima); rol activo: " + usuario.getRolPrincipal() + ".\n"
                + "Mensaje del usuario: " + message;

        var turn = tools.newTurn();
        try {
            var response = chatClient.prompt()
                    .messages(history)
                    .user(contexto)
                    .toolCallbacks(turn.callbacks())
                    .call().chatResponse();
            if (response == null || response.getResult() == null
                    || response.hasToolCalls()
                    || !"STOP".equalsIgnoreCase(response.getResult().getMetadata().getFinishReason())) {
                throw AgentToolRegistry.invalid("INVALID_RESPONSE", "El proveedor devolvió una respuesta incompleta.");
            }
            String text = response.getResult().getOutput().getText();
            if (text == null || text.isBlank() || text.length() > 8000
                    || text.stripLeading().startsWith("{") || text.stripLeading().startsWith("```")) {
                throw AgentToolRegistry.invalid("INVALID_RESPONSE", "El proveedor devolvió una respuesta inválida.");
            }
            return turn.response(text.trim());
        } catch (RuntimeException failure) {
            // No model retries: an earlier tool may already have completed.
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof NoPermitidoException denied) throw denied;
                if (cause instanceof NoAutorizadoException denied) throw denied;
                if (!turn.hasResults()) {
                    if (cause instanceof com.uniride.exceptions.CamposInvalidosException invalid) throw invalid;
                    if (cause instanceof com.uniride.exceptions.BusinessException business) throw business;
                    if (cause instanceof com.uniride.exceptions.ResourceNotFoundException missing) throw missing;
                }
            }
            if (turn.hasResults()) {
                var controlled = providerFailure(failure);
                return turn.response("El proveedor no completó la explicación. Estos son los resultados obtenidos; no se repitió ninguna acción.",
                        new com.uniride.agent.dto.AdvertenciaAgente(controlled.code(), controlled.getMessage()));
            }
            throw providerFailure(failure);
        }
    }

    private AgentProviderException providerFailure(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.InterruptedIOException || cause instanceof java.net.http.HttpTimeoutException)
                return new AgentProviderException(HttpStatus.GATEWAY_TIMEOUT,
                        "PROVIDER_TIMEOUT", "El proveedor de IA superó el tiempo de espera.");
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AgentProviderException controlled) return controlled;
            if (cause instanceof OpenAIServiceException provider) {
                if (provider.statusCode() == 429) return new AgentProviderException(HttpStatus.TOO_MANY_REQUESTS,
                        "PROVIDER_RATE_LIMIT", "El proveedor alcanzó su límite de uso. Intenta más tarde.");
                if (provider.statusCode() == 401 || provider.statusCode() == 403 || provider.statusCode() >= 500)
                    return new AgentProviderException(HttpStatus.SERVICE_UNAVAILABLE,
                            "PROVIDER_UNAVAILABLE", "El proveedor de IA no está disponible temporalmente.");
                return AgentToolRegistry.invalid("PROVIDER_REJECTED", "El proveedor rechazó la respuesta o la llamada a una herramienta. No se reintentó la conversación.");
            }
            if (cause instanceof com.openai.errors.OpenAIIoException)
                return new AgentProviderException(HttpStatus.SERVICE_UNAVAILABLE,
                        "PROVIDER_UNAVAILABLE", "No se pudo contactar al proveedor de IA.");
        }
        return AgentToolRegistry.invalid("INVALID_TOOL_RESPONSE", "El agente no pudo completar la respuesta. No se reintentó ninguna acción.");
    }
}
