package com.uniride.agent;

import com.uniride.agent.dto.ChatResponse;
import com.uniride.agent.dto.AclaracionAgente;
import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.dto.AdvertenciaAgente;
import com.uniride.agent.dto.ContribucionResultado;
import com.uniride.agent.dto.ResolucionConfirmacion;
import com.uniride.agent.dto.ResolucionConfirmacion.Estado;
import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import com.uniride.services.UsuarioService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolExecutionResult;
import com.openai.errors.OpenAIServiceException;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.exceptions.NoAutorizadoException;
import com.uniride.exceptions.ResourceNotFoundException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class AgentService {

    private static final DateTimeFormatter FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern POSIBLE_SOLICITUD_COMPUESTA = Pattern.compile(
            "(?iu)[,;\\r\\n]|[.!?]\\s+\\S|\\b(?:y|e|o|adem[aá]s|tambi[eé]n|luego|despu[eé]s|"
                    + "junto con|as[ií] como|a la vez)\\b");

    private final ChatClient chatClient;
    private final UsuarioService usuarioService;
    private final Clock clock;
    private final AgentToolRegistry tools;
    private final AgentConversationStore conversations;
    private final ConfirmationService confirmations;

    public AgentService(ChatClient chatClient, UsuarioService usuarioService, Clock clock,
                        AgentToolRegistry tools, AgentConversationStore conversations, ConfirmationService confirmations) {
        this.chatClient = chatClient; this.usuarioService = usuarioService; this.clock = clock;
        this.tools = tools; this.conversations = conversations; this.confirmations = confirmations;
    }

    /** Compatibility for internal callers. Identity always comes from SecurityContext via UsuarioService. */
    public ChatResponse chat(String username, String message) {
        return chat(message, (UUID) null);
    }

    public ChatResponse chat(String message, UUID conversationId) {
        return chat(message, conversationId, false);
    }

    /** Same orchestration as the text chat; only the response style changes. */
    public ChatResponse chatVoz(String message, UUID conversationId) {
        return chat(message, conversationId, true);
    }

    private ChatResponse chat(String message, UUID conversationId, boolean voz) {
        AgentConversationStore.validateMessage(message);
        Usuario usuario = usuarioService.usuarioActual();
        try (var lease = conversations.open(usuario.getId(), usuario.getRolPrincipal(), conversationId)) {
            var activa = lease.aclaracion();
            var campos = EnvioAclaracion.leer(message, activa);
            String anterior = campos == null ? message : EnvioAclaracion.paraHistorial(activa, campos);
            ChatResponse response;
            var decision = campos == null ? ConfirmationIntent.classify(message) : ConfirmationIntent.OTRA;
            var borradorContribucion = lease.contribucion();
            var borradorRuta = lease.rutaOViaje();
            var contribucion = campos == null ? ContribucionIntent.classify(message, borradorContribucion, clock)
                    : "sugerirContribucionViaje".equals(activa.operacion())
                    ? ContribucionIntent.continuarCampos(campos, borradorContribucion, clock) : null;
            var anteriorPdf = lease.historial();
            var historial = historial(message, activa, campos, anteriorPdf);
            var rutaOViaje = campos == null ? IntencionAclarable.inicioLocal(message, lease.ultimoContexto(), clock)
                    : borradorRuta == null ? null : borradorRuta.continuar(campos);
            if (rutaOViaje == null && campos == null && decision == ConfirmationIntent.OTRA && activa != null) {
                rutaOViaje = IntencionAclarable.continuar(message, borradorRuta, activa, lease.ultimoContexto(), clock);
            }
            var compensation = campos == null ? CompensationIntent.classify(message, lease.borrador())
                    : lease.borrador() == null ? null : CompensationIntent.continuarCampos(campos, lease.borrador());
            if (campos != null && contribucion == null && historial == null && rutaOViaje == null && compensation == null)
                throw new CamposInvalidosException("Revisa los campos de la aclaración e intenta de nuevo.");

            lease.guardarAclaracion(null);
            lease.guardarRutaOViaje(null);
            lease.guardarHistorial(null);
            lease.guardarContribucion(null);
            if (contribucion != null) {
                if (usuario.getRolPrincipal() != Rol.PASAJERO)
                    throw new NoPermitidoException("Esta consulta requiere el rol PASAJERO.");
                lease.descartarReferenciaConfirmacion();
                lease.guardarBorrador(null);
                lease.guardarContexto(null);
                if (contribucion.aclaracion() != null) {
                    if (contribucion.filtros() != null) {
                        lease.guardarContribucion(new ContribucionIntent.Borrador(contribucion.filtros(),
                                ContribucionResultado.Estado.FALTAN_FILTROS));
                    }
                    response = soloMensaje(contribucion.aclaracion()).conAclaracion(contribucion.aclaracionEstructurada());
                } else {
                    response = execute(usuario, anterior, lease, CompensationIntent.classify("", null), contribucion,
                            null, null, false, voz);
                }
            } else if (decision == ConfirmationIntent.CONFIRMAR || decision == ConfirmationIntent.CANCELAR) {
                response = resolverConfirmacion(lease, decision == ConfirmationIntent.CONFIRMAR);
            } else if (decision == ConfirmationIntent.AMBIGUA && lease.confirmacionPendiente() != null) {
                lease.guardarBorrador(null);
                lease.descartarReferenciaConfirmacion();
                response = respuestaConfirmacion(new ResolucionConfirmacion(Estado.ACLARACION_REQUERIDA, null), null);
            } else {
                // A new request may yield a new receipt, but cannot resume an older confirmation reference.
                lease.descartarReferenciaConfirmacion();
                if (rutaOViaje != null && usuario.getRolPrincipal() != rutaOViaje.accion().rol)
                    throw new NoPermitidoException("Esta acción requiere el rol " + rutaOViaje.accion().rol + ".");
                if (compensation == null || rutaOViaje != null || historial != null && campos != null)
                    compensation = CompensationIntent.classify("", null);
                if (rutaOViaje == null) lease.guardarContexto(null);
                lease.guardarBorrador(compensation.draft());
                boolean aclaracionLocal = compensation.message() != null && (compensation.informational()
                        || compensation.draft() != null || !POSIBLE_SOLICITUD_COMPUESTA.matcher(message).find());
                response = aclaracionLocal ? soloMensaje(compensation.message()).conAclaracion(compensation.aclaracion())
                        : execute(usuario, anterior, lease, compensation, null, historial, rutaOViaje,
                        campos != null || historial != null && anteriorPdf != null
                                && HistorialPdfIntent.classify(message, clock) == null, voz);
                lease.guardarBorrador(CompensationIntent.afterResponse(compensation, response.operacion()));
                if (historial != null && response.aclaracion() != null
                        && response.aclaracion().operacion().equals("prepararDescargaHistorialPdf")) {
                    lease.guardarHistorial(historial);
                }
            }
            AclaracionAgente esquema = response.aclaracion();
            if (esquema != null) {
                esquema = esquema.conId(UUID.randomUUID());
                lease.guardarAclaracion(esquema);
                response = response.conAclaracion(esquema);
            }
            response = response.withConversationId(lease.id());
            lease.complete(anterior, response);
            return response;
        }
    }

    private HistorialPdfIntent.Solicitud historial(String message, AclaracionAgente activa, Map<String, String> campos,
                                                   HistorialPdfIntent.Solicitud anterior) {
        if (campos != null) {
            return "prepararDescargaHistorialPdf".equals(activa.operacion())
                    ? HistorialPdfIntent.continuarCampos(campos, anterior, clock) : null;
        }
        var actual = HistorialPdfIntent.classify(message, clock);
        return actual != null ? actual : HistorialPdfIntent.continuar(message, anterior, clock);
    }

    private ChatResponse resolverConfirmacion(AgentConversationStore.Lease lease, boolean confirmar) {
        UUID id = lease.confirmacionPendiente();
        if (!confirmar || id != null) lease.guardarBorrador(null);
        if (id == null) {
            var resuelta = lease.confirmacionResuelta();
            if (resuelta == null) {
                return respuestaConfirmacion(new ResolucionConfirmacion(Estado.SIN_PENDIENTE, null), null);
            }
            var estado = switch (resuelta.estado()) {
                case EJECUTADA -> Estado.YA_EJECUTADA;
                case CANCELADA -> Estado.YA_CANCELADA;
                case EXPIRADA -> Estado.EXPIRADA;
                case PENDIENTE -> throw new IllegalStateException("La confirmación aún está pendiente");
            };
            return respuestaConfirmacion(new ResolucionConfirmacion(estado, resuelta), null);
        }

        try {
            var resolucion = confirmations.resolverDesdeChat(id, confirmar);
            lease.guardarResolucion(resolucion.confirmacion());
            return respuestaConfirmacion(resolucion, null);
        } catch (CamposInvalidosException invalid) {
            return rechazarConfirmacion("CONFIRMATION_INVALID", invalid.getMessage());
        } catch (ResourceNotFoundException missing) {
            return rechazarConfirmacion("CONFIRMATION_NOT_FOUND", missing.getMessage());
        } catch (BusinessException conflict) {
            return rechazarConfirmacion("CONFIRMATION_BUSINESS_CONFLICT", conflict.getMessage());
        } catch (DataIntegrityViolationException conflict) {
            return rechazarConfirmacion("CONFIRMATION_BUSINESS_CONFLICT", "Los datos actuales impiden aplicar la operación.");
        } catch (TransactionException | TransientDataAccessException | DataAccessResourceFailureException
                 | RecoverableDataAccessException | JpaSystemException uncertain) {
            return respuestaConfirmacion(new ResolucionConfirmacion(Estado.RESULTADO_INCIERTO, null),
                    new AdvertenciaAgente("CONFIRMATION_RESULT_UNCERTAIN",
                            "No se pudo determinar el resultado. Consulta el estado en la aplicación o con GET "
                                    + "/api/agent/confirmaciones/" + id + " antes de reintentar."));
        }
    }

    private ChatResponse rechazarConfirmacion(String codigo, String mensaje) {
        return respuestaConfirmacion(new ResolucionConfirmacion(Estado.RECHAZADA, null), new AdvertenciaAgente(codigo, mensaje));
    }

    private ChatResponse respuestaConfirmacion(ResolucionConfirmacion resolucion, AdvertenciaAgente advertencia) {
        var operacion = resolucion.estado() == Estado.EJECUTADA ? resolucion.confirmacion().resultado() : null;
        String mensaje = switch (resolucion.estado()) {
            case EJECUTADA -> operacion.detalle();
            case CANCELADA -> "Cancelé la confirmación. La operación quedó sin ejecutar.";
            case EXPIRADA -> "La confirmación expiró. Solicita una nueva propuesta.";
            case YA_EJECUTADA -> "La operación ya fue ejecutada. " + resolucion.confirmacion().resultado().detalle();
            case YA_CANCELADA -> "La confirmación ya estaba cancelada. La operación quedó sin ejecutar.";
            case SIN_PENDIENTE -> "No hay una confirmación pendiente en esta conversación.";
            case ACLARACION_REQUERIDA -> "La propuesta quedó sin resolver. Indica qué cambio necesitas para preparar otra "
                    + "o revisa la propuesta anterior en la aplicación.";
            case RECHAZADA, RESULTADO_INCIERTO -> advertencia.mensaje();
        };
        return new ChatResponse(mensaje, List.of(), List.of(), List.of(), operacion, List.of(), advertencia, null, resolucion);
    }

    private ChatResponse soloMensaje(String message) {
        return new ChatResponse(message, List.of(), List.of(), List.of(), null);
    }

    private ChatResponse execute(Usuario usuario, String message, AgentConversationStore.Lease lease,
                                 CompensationIntent.Decision compensation, ContribucionIntent.Solicitud contribucion,
                                 HistorialPdfIntent.Solicitud historial, IntencionAclarable.Borrador rutaOViaje,
                                 boolean continuacion, boolean voz) {
        var history = lease.providerHistory();
        boolean solicitudViaje = CompensationIntent.isTripRequest(message) || (history.size() >= 2
                && history.get(history.size() - 2) instanceof UserMessage anterior
                && CompensationIntent.isTripRequest(anterior.getText()));
        var turn = tools.newTurn(historial,
                compensation, solicitudViaje, contribucion == null ? null : contribucion.filtros());
        turn.contextoAclaracion(message, usuario.getRolPrincipal(), clock);
        try {
            if (contribucion != null) {
                turn.recoverContribucion();
                return turn.response("");
            }
            if (compensation.recoveryArgs() != null) {
                turn.recoverCompensation();
                return turn.response("");
            }
            if (rutaOViaje != null && turn.tieneHerramienta(rutaOViaje.accion().herramienta)) {
                turn.procesar(rutaOViaje);
                return turn.response("");
            }
            if (historial != null && (continuacion || historial.aclaracionEstructurada() != null
                    && !POSIBLE_SOLICITUD_COMPUESTA.matcher(message).find())) {
                turn.recoverReports(java.util.Set.of());
                return turn.response("");
            }
            if (CompensationIntent.isOwnMethodList(message)) {
                turn.consultOwnMethods();
                return turn.response("");
            }
            var reportes = AgentReportIntent.classify(message);
            if (reportes.contains(AgentReportIntent.RANKING) && usuario.getRolPrincipal() != Rol.CONDUCTOR) {
                throw new NoPermitidoException("Esta consulta requiere el rol CONDUCTOR.");
            }
            String text = providerAnswer(usuario.getRolPrincipal(), message, history, turn, voz);
            if (!turn.hasResults() && reportes.isEmpty() && historial == null
                    && !POSIBLE_SOLICITUD_COMPUESTA.matcher(message).find()) {
                var borrador = IntencionAclarable.siFaltanDatos(message, usuario.getRolPrincipal(), clock);
                if (borrador != null && turn.tieneHerramienta(borrador.accion().herramienta)) turn.procesar(borrador);
            }
            turn.recoverReports(reportes);
            return turn.response(text);
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
                String detail = "PROVIDER_RATE_LIMIT".equals(controlled.code()) ? ""
                        : "El proveedor no completó la explicación. Estos son los resultados obtenidos; no se repitió ninguna acción.";
                return turn.response(detail, new AdvertenciaAgente(controlled.code(), controlled.getMessage()));
            }
            throw providerFailure(failure);
        } finally {
            if (contribucion != null && turn.contribucion() != null) {
                lease.guardarContribucion(contribucion.siguiente(turn.contribucion()));
            }
            if (turn.rutaOViaje() != null) {
                if (turn.propuestaPendiente()) lease.guardarContexto(turn.rutaOViaje().contexto());
                else if (turn.tieneAclaracion()) lease.guardarRutaOViaje(turn.rutaOViaje());
            }
        }
    }

    private String providerAnswer(Rol role, String message, List<Message> history, AgentToolRegistry.Turn turn, boolean voz) {
        String contexto = "Lima: " + LocalDate.now(clock) + " " + LocalTime.now(clock).format(FORMATO_HORA)
                + ". Rol activo: " + role + (voz ? ". Respuesta para voz." : ".") + "\n" + message;
        boolean terminalResults = !POSIBLE_SOLICITUD_COMPUESTA.matcher(message).find();
        var callbacks = turn.callbacks(role, terminalResults);
        try (var diagnostics = GroqHttpDiagnostics.open()) {
            try {
                var response = chatClient.prompt().messages(history).user(contexto).toolCallbacks(callbacks)
                        .call().chatResponse();
                if (response == null || response.getResult() == null || response.hasToolCalls()) {
                    throw AgentToolRegistry.invalid("INVALID_RESPONSE", "El proveedor devolvió una respuesta incompleta.");
                }
                String reason = response.getResult().getMetadata().getFinishReason();
                if (ToolExecutionResult.FINISH_REASON.equals(reason) && turn.hasDirectResult()) return "";
                if (!"STOP".equalsIgnoreCase(reason)) {
                    throw AgentToolRegistry.invalid("INVALID_RESPONSE", "El proveedor devolvió una respuesta incompleta.");
                }
                String text = response.getResult().getOutput().getText();
                if (text == null || text.isBlank() || text.length() > 8000
                        || text.stripLeading().startsWith("{") || text.stripLeading().startsWith("```")) {
                    throw AgentToolRegistry.invalid("INVALID_RESPONSE", "El proveedor devolvió una respuesta inválida.");
                }
                return text.trim();
            } catch (RuntimeException failure) {
                diagnostics.recordRateLimit(failure);
                throw failure;
            }
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
