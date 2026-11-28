package com.uniride.agent.conversation;

import com.uniride.agent.confirmation.EstadoConfirmacion;
import com.uniride.agent.dto.AclaracionAgente;
import com.uniride.agent.dto.ChatResponse;
import com.uniride.agent.dto.ConfirmacionResumen;
import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.support.AgentProviderException;
import com.uniride.enums.Rol;
import java.time.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.*;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ephemeral, bounded sessions. UUID is a locator, never proof of ownership. */
@Component
public class AgentConversationStore {
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_SESSIONS = 1000;
    private static final int MAX_PER_USER = 5;
    private static final int PROVIDER_HISTORY_MESSAGES = 8;
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(?:bearer\\s+\\S+|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+|gsk_[A-Za-z0-9]{12,}|sk-[A-Za-z0-9_-]{12,}|(?:password|contrase[nñ]a|clave(?: de api)?|api[_ -]?key|jwt_secret|groq_api_key)[\"']?\\s*(?:[:=]|\\b(?:es|is)\\b)\\s*[\"']?\\S+)");
    private final Clock clock;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public AgentConversationStore(Clock clock) { this.clock = clock; }

    public static void validateMessage(String message) {
        if (message == null || message.isBlank() || message.length() > 1000)
            throw new AgentProviderException(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                    "El mensaje debe contener entre 1 y 1000 caracteres.");
        validateNoSecrets(message);
    }

    public static void validateNoSecrets(String value) {
        if (containsSecrets(value))
            throw new AgentProviderException(HttpStatus.BAD_REQUEST, "SENSITIVE_MESSAGE",
                    "Retira contraseñas, tokens y claves del mensaje antes de enviarlo.");
    }

    public static boolean containsSecrets(String value) {
        return value != null && SECRET.matcher(value).find();
    }

    public synchronized Lease open(Long userId, Rol role, UUID requested) {
        cleanup();
        Session session;
        UUID id = requested;
        if (id == null) {
            List<Map.Entry<UUID, Session>> owned = sessions.entrySet().stream()
                    .filter(e -> e.getValue().userId.equals(userId))
                    .sorted(Comparator.comparing(e -> e.getValue().lastUsed)).toList();
            if (owned.size() >= MAX_PER_USER) {
                var oldest = owned.stream().filter(e -> !e.getValue().lock.isLocked()).findFirst()
                        .orElseThrow(() -> limit("Todas tus conversaciones están en uso."));
                sessions.remove(oldest.getKey());
            }
            if (sessions.size() >= MAX_SESSIONS) throw limit("El agente alcanzó el límite de conversaciones. Intenta más tarde.");
            id = UUID.randomUUID();
            session = new Session(userId, role, clock.instant());
            sessions.put(id, session);
        } else {
            session = sessions.get(id);
            if (session == null || !session.userId.equals(userId))
                throw new AgentProviderException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND",
                        "La conversación no existe o expiró. Inicia una nueva.");
            if (session.role != role) {
                sessions.remove(id);
                throw new AgentProviderException(HttpStatus.CONFLICT, "CONVERSATION_ROLE_CHANGED",
                        "Tu rol cambió. Inicia una nueva conversación.");
            }
        }
        // ReentrantLock.tryLock would succeed recursively on the same thread; reject that too.
        if (session.lock.isLocked() || !session.lock.tryLock())
            throw new AgentProviderException(HttpStatus.CONFLICT, "CONVERSATION_BUSY",
                    "Hay un mensaje en curso en esta conversación.");
        if (session.turns >= 100) {
            session.lock.unlock();
            throw limit("Esta conversación alcanzó 100 mensajes. Inicia una nueva.");
        }
        session.turns++;
        session.lastUsed = clock.instant();
        return new Lease(id, session);
    }

    @Scheduled(fixedDelay = 60000)
    public synchronized void cleanup() {
        Instant cutoff = clock.instant().minus(TTL);
        sessions.entrySet().removeIf(e -> !e.getValue().lock.isLocked()
                && !e.getValue().lastUsed.isAfter(cutoff));
    }

    private static AgentProviderException limit(String message) {
        return new AgentProviderException(HttpStatus.TOO_MANY_REQUESTS, "CONVERSATION_LIMIT", message);
    }

    public sealed interface Draft permits CompensationIntent.Draft, ContribucionIntent.Borrador,
            IntencionAclarable.Borrador, HistorialPdfIntent.Solicitud, CambioPersonal { }

    public record CambioPersonal(String herramienta, Map<String, Object> argumentos) implements Draft {
        public CambioPersonal { argumentos = Map.copyOf(argumentos); }
    }

    private static final class Session {
        private final Long userId;
        private final Rol role;
        private final ReentrantLock lock = new ReentrantLock();
        private final Deque<Message> messages = new ArrayDeque<>();
        private volatile Instant lastUsed;
        private int turns;
        private Draft draft;
        private AclaracionAgente aclaracion;
        private IntencionAclarable.Contexto ultimoContexto;
        private CoordinacionConductorIntent.Sesion coordinacion;
        private UUID confirmacionPendiente;
        private ConfirmacionResumen confirmacionResuelta;
        private Session(Long userId, Rol role, Instant now) {
            this.userId = userId; this.role = role; this.lastUsed = now;
        }
    }

    public final class Lease implements AutoCloseable {
        private final UUID id;
        private final Session session;
        private boolean closed;
        private Lease(UUID id, Session session) { this.id = id; this.session = session; }
        public UUID id() { return id; }
        public List<Message> history() { return List.copyOf(session.messages); }

        public List<Message> providerHistory() {
            var history = history();
            return history.subList(Math.max(0, history.size() - PROVIDER_HISTORY_MESSAGES), history.size());
        }

        private <T extends Draft> T draft(Class<T> tipo) {
            return tipo.isInstance(session.draft) ? tipo.cast(session.draft) : null;
        }

        private <T extends Draft> void guardarDraft(Class<T> tipo, T valor) {
            if (valor != null || tipo.isInstance(session.draft)) session.draft = valor;
        }

        public void limpiarContinuacion() {
            session.draft = null;
            session.aclaracion = null;
        }

        public Draft borradorActivo() { return session.draft; }

        public void guardarBorradorActivo(Draft draft) {
            if (draft instanceof CompensationIntent.Draft compensacion) compensacion.validateNoSecrets();
            session.draft = draft;
        }

        public CompensationIntent.Draft borrador() { return draft(CompensationIntent.Draft.class); }

        public void guardarBorrador(CompensationIntent.Draft borrador) {
            if (borrador != null) borrador.validateNoSecrets();
            guardarDraft(CompensationIntent.Draft.class, borrador);
        }

        public ContribucionIntent.Borrador contribucion() { return draft(ContribucionIntent.Borrador.class); }

        public void guardarContribucion(ContribucionIntent.Borrador borrador) {
            guardarDraft(ContribucionIntent.Borrador.class, borrador);
        }

        public AclaracionAgente aclaracion() { return session.aclaracion; }
        public void guardarAclaracion(AclaracionAgente aclaracion) { session.aclaracion = aclaracion; }
        public IntencionAclarable.Borrador rutaOViaje() { return draft(IntencionAclarable.Borrador.class); }
        public void guardarRutaOViaje(IntencionAclarable.Borrador borrador) { guardarDraft(IntencionAclarable.Borrador.class, borrador); }
        public IntencionAclarable.Contexto ultimoContexto() { return session.ultimoContexto; }
        public void guardarContexto(IntencionAclarable.Contexto contexto) { session.ultimoContexto = contexto; }
        public CoordinacionConductorIntent.Sesion coordinacion() { return session.coordinacion; }
        public void guardarCoordinacion(CoordinacionConductorIntent.Sesion coordinacion) { session.coordinacion = coordinacion; }
        public HistorialPdfIntent.Solicitud historial() { return draft(HistorialPdfIntent.Solicitud.class); }
        public void guardarHistorial(HistorialPdfIntent.Solicitud historial) { guardarDraft(HistorialPdfIntent.Solicitud.class, historial); }

        public UUID confirmacionPendiente() { return session.confirmacionPendiente; }
        public ConfirmacionResumen confirmacionResuelta() { return session.confirmacionResuelta; }

        public void descartarReferenciaConfirmacion() {
            session.confirmacionPendiente = null;
            session.confirmacionResuelta = null;
        }

        public void guardarResolucion(ConfirmacionResumen confirmacion) {
            session.confirmacionPendiente = null;
            session.confirmacionResuelta = confirmacion;
        }

        public void complete(String user, ChatResponse response) {
            complete(user, response, response.message());
        }

        public void complete(String user, ChatResponse response, String textoParaHistorial) {
            var operacion = response.operacion();
            if (operacion != null && operacion.estado() == EstadoOperacion.CONFIRMACION_REQUERIDA
                    && operacion.confirmacion() != null && operacion.confirmacion().estado() == EstadoConfirmacion.PENDIENTE) {
                session.confirmacionPendiente = operacion.confirmacion().id();
                session.confirmacionResuelta = null;
            }
            complete(user, textoParaHistorial);
        }

        public void complete(String user, String assistant) {
            // Credential-shaped output is never retained, even if an external provider echoes it.
            if (SECRET.matcher(assistant).find()) return;
            session.messages.addLast(new UserMessage(user));
            session.messages.addLast(new AssistantMessage(assistant));
            while (session.messages.size() > 20) session.messages.removeFirst();
        }
        public void close() {
            if (!closed) {
                session.lastUsed = clock.instant();
                closed = true;
                session.lock.unlock();
            }
        }
    }
}
