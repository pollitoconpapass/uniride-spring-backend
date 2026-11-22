package com.uniride.agent.confirmation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.uniride.agent.AgentConversationFixture;
import com.uniride.agent.AgentConversationStore;
import com.uniride.agent.AgentService;
import com.uniride.agent.AgentToolRegistry;
import com.uniride.agent.dto.*;
import com.uniride.dto.requests.*;
import com.uniride.entities.*;
import com.uniride.enums.*;
import com.uniride.exceptions.*;
import com.uniride.repositories.*;
import com.uniride.security.JwtService;
import com.uniride.services.*;
import jakarta.servlet.Filter;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Runs against the isolated test datasource only, with no model/provider calls. */
@SpringBootTest
class ConfirmationServiceIntegrationTest {
    @Autowired ConfirmationService service;
    @Autowired AgentToolRegistry agentTools;
    @Autowired AgentConversationStore conversations;
    @Autowired JwtService jwt;
    @Autowired WebApplicationContext web;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityChain;
    @Autowired UsuarioService userService;
    @Autowired ConfirmationRepository confirmations;
    @Autowired UsuarioRepository users;
    @Autowired PerfilRepository profiles;
    @Autowired RutaRepository routes;
    @Autowired ViajeRepository trips;
    @Autowired SolicitudRepository requests;
    @Autowired MetodoCompensacionRepository methods;
    @Autowired MetodoCompensacionService methodService;
    @Autowired TransactionTemplate transaction;
    @Autowired ObjectMapper mapper;
    @Autowired PerfilRolService roles;
    @Autowired Clock clock;
    @MockitoBean NotificacionService notifications;
    @MockitoBean ChatClient chatClient;
    private MockMvc mvc;
    Usuario driver;
    Usuario passenger;

    @BeforeEach void fixtures() {
        driver = user(Rol.CONDUCTOR);
        passenger = user(Rol.PASAJERO);
        profiles.save(Perfil.builder().usuario(driver).nombre("Ana").apellidos("Prueba")
                .carrera("Software").distrito("Surco").universidad("UPC").diasDisponibles("Lunes")
                .horariosDisponibles("08:00").fechaInicioClases(LocalDate.of(2026, 3, 1))
                .fechaFinClases(LocalDate.of(2026, 12, 20)).build());
        authenticate(driver);
    }

    @AfterEach void clearIdentity() {
        SecurityContextHolder.clearContext();
        verifyNoInteractions(chatClient);
    }

    @Test void recoveredActivationStaysPendingUntilTheUserConfirmsItInChat() {
        var original = metodo(driver, TipoCompensacion.GASOLINA, "glp", false);
        var providerCalls = new AtomicInteger();
        ChatModel model = prompt -> {
            providerCalls.incrementAndGet();
            var generation = new Generation(new AssistantMessage("¿Prefieres compensar con DINERO, GASOLINA o FAVOR?"),
                    ChatGenerationMetadata.builder().finishReason("STOP").build());
            return new org.springframework.ai.chat.model.ChatResponse(List.of(generation));
        };
        var agent = new AgentService(ChatClient.builder(model).build(), userService, clock, agentTools,
                conversations, service);
        long before = confirmations.count();

        var proposal = agent.chat("Activa mi método de compensación gasolina", (UUID) null);
        var pending = proposal.operacion().confirmacion();
        assertThat(proposal.operacion().estado()).isEqualTo(EstadoOperacion.CONFIRMACION_REQUERIDA);
        assertThat(pending.tipo()).isEqualTo(TipoOperacion.CAMBIAR_ESTADO_METODO);
        assertThat(pending.parametros().path("activo").asBoolean()).isTrue();
        assertThat(proposal.resultados()).extracting(ResultadoAgente::tipo).containsExactly("cambiarEstadoMetodoCompensacion");
        assertThat(proposal.message()).contains("glp", "sí", "cancelar").doesNotContain("¿Prefieres");
        assertThat(confirmations.findById(pending.id()).orElseThrow().getEstado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        assertMetodo(original.getId(), TipoCompensacion.GASOLINA, "glp", false);
        verifyNoInteractions(notifications);

        var acceptance = agent.chat("sí", proposal.conversationId());
        assertThat(acceptance.conversationId()).isEqualTo(proposal.conversationId());
        assertThat(acceptance.resolucionConfirmacion().estado().name()).isEqualTo("EJECUTADA");
        assertThat(acceptance.operacion()).isEqualTo(service.obtener(pending.id()).resultado());
        assertThat(acceptance.message()).isEqualTo(acceptance.operacion().detalle());
        assertThat(providerCalls).hasValue(1);
        assertThat(confirmations.count()).isEqualTo(before + 1);
        assertMetodo(original.getId(), TipoCompensacion.GASOLINA, "glp", true);
        verify(notifications).notificar(any(), eq(TipoNotificacion.EXITO), eq("Método de compensación actualizado"), anyString());
    }

    @ParameterizedTest
    @EnumSource(Rol.class)
    void recoveredEditAndDeletionPrepareOnlyOwnedMethodsForEitherRole(Rol role) {
        var owner = role == Rol.CONDUCTOR ? driver : passenger;
        var other = role == Rol.CONDUCTOR ? passenger : driver;
        authenticate(owner);
        var yape = metodo(owner, TipoCompensacion.DINERO, "Yape", false);
        var favor = metodo(owner, TipoCompensacion.FAVOR, "Apuntes", true);
        var foreign = metodo(other, TipoCompensacion.FAVOR, "Apuntes", true);
        ChatModel model = prompt -> new org.springframework.ai.chat.model.ChatResponse(List.of(
                new Generation(new AssistantMessage("Operación ejecutada."),
                        ChatGenerationMetadata.builder().finishReason("STOP").build())));
        var agent = new AgentService(ChatClient.builder(model).build(), userService, clock, agentTools,
                conversations, service);
        long before = confirmations.count();

        var question = agent.chat("Cambia la descripción de mi método Yape", (UUID) null);
        assertThat(question.operacion()).isNull();
        assertThat(question.message()).containsIgnoringCase("nueva descripción");
        assertThat(confirmations.count()).isEqualTo(before);

        var edit = agent.chat("Yape personal", question.conversationId());
        var editPending = edit.operacion().confirmacion();
        assertThat(editPending.tipo()).isEqualTo(TipoOperacion.ACTUALIZAR_METODO);
        assertThat(editPending.parametros().path("descripcion").asText()).isEqualTo("Yape personal");
        assertThat(editPending.parametros().path("activo").asBoolean()).isFalse();

        var deletion = agent.chat("Elimina mi método FAVOR", edit.conversationId());
        var deletePending = deletion.operacion().confirmacion();
        assertThat(deletePending.tipo()).isEqualTo(TipoOperacion.ELIMINAR_METODO);
        assertThat(deletePending.resumen()).contains("Apuntes");
        assertThat(confirmations.count()).isEqualTo(before + 2);
        assertMetodo(yape.getId(), TipoCompensacion.DINERO, "Yape", false);
        assertThat(methods.existsById(favor.getId())).isTrue();
        assertThat(methods.existsById(foreign.getId())).isTrue();
        verifyNoInteractions(notifications);

        var acceptance = agent.chat("sí", deletion.conversationId());
        assertThat(acceptance.resolucionConfirmacion().estado().name()).isEqualTo("EJECUTADA");
        assertThat(acceptance.resolucionConfirmacion().confirmacion().id()).isEqualTo(deletePending.id());
        assertThat(acceptance.operacion().id()).isEqualTo(favor.getId());
        assertThat(service.obtener(editPending.id()).estado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        assertThat(service.confirmar(editPending.id()).estado()).isEqualTo(EstadoConfirmacion.EJECUTADA);
        assertMetodo(yape.getId(), TipoCompensacion.DINERO, "Yape personal", false);
        assertThat(methods.existsById(favor.getId())).isFalse();
        assertThat(methods.existsById(foreign.getId())).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "CREAR_RUTA, sí",
        "CREAR_VIAJE, confirmo",
        "SOLICITAR_UNIRSE, adelante",
        "REGISTRAR_METODO, 'sí, confirmo'",
        "ACTUALIZAR_METODO, hazlo",
        "CAMBIAR_ESTADO_METODO, procede",
        "ELIMINAR_METODO, confirmar",
        "EDITAR_RUTA, sí",
        "ELIMINAR_RUTA, confirmo",
        "REPROGRAMAR_VIAJE, sí",
        "CANCELAR_SOLICITUD, adelante"
    })
    void acceptsAllPersistedOperationTypesInChat(TipoOperacion type, String response) throws Exception {
        Usuario owner = switch (type) {
            case SOLICITAR_UNIRSE, CANCELAR_SOLICITUD -> passenger;
            default -> driver;
        };
        authenticate(owner);
        var original = switch (type) {
            case ACTUALIZAR_METODO, CAMBIAR_ESTADO_METODO, ELIMINAR_METODO ->
                    metodo(owner, TipoCompensacion.DINERO, "Yape", true);
            default -> null;
        };
        var prepared = switch (type) {
            case CREAR_RUTA -> service.prepararRuta(routeRequest());
            case CREAR_VIAJE -> service.prepararViaje(route().getId(), tripRequest());
            case SOLICITAR_UNIRSE -> service.prepararSolicitud(trip().getId(),
                    new SolicitudRequest(null, TipoCompensacion.GASOLINA, null));
            case REGISTRAR_METODO -> service.prepararMetodo(
                    new MetodoCompensacionRequest(TipoCompensacion.FAVOR, "Compartir apuntes", true));
            case ACTUALIZAR_METODO -> service.prepararActualizacionMetodo(null, "Yape", null, "Yape personal");
            case CAMBIAR_ESTADO_METODO -> service.prepararEstadoMetodo(null, "Yape", false);
            case ELIMINAR_METODO -> service.prepararEliminacionMetodo(null, "Yape");
            case EDITAR_RUTA -> service.prepararEdicionRuta(route().getId(), null, 5, null, null, null);
            case ELIMINAR_RUTA -> service.prepararEliminacionRuta(route().getId());
            case REPROGRAMAR_VIAJE -> service.prepararReprogramacionViaje(trip().getId(),
                    tripRequest().fecha().plusDays(1), LocalTime.of(9, 0));
            case CANCELAR_SOLICITUD -> {
                Viaje viaje = trip();
                Solicitud solicitud = requests.save(Solicitud.builder().viaje(viaje).pasajero(passenger)
                        .preferenciaCompensacion(TipoCompensacion.FAVOR).estado(EstadoSolicitud.PENDIENTE).build());
                yield service.prepararCancelacionSolicitudes(List.of(new SolicitudResumen(solicitud.getId(),
                        viaje.getId(), viaje.getRuta().getOrigen(), viaje.getRuta().getDestino(), viaje.getDia(),
                        viaje.getFecha(), viaje.getHora(), EstadoSolicitud.PENDIENTE)));
            }
        };
        UUID id = prepared.confirmacion().id();
        UUID conversation = bindProposal(owner, prepared);
        assertThat(service.obtener(id).estado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        verifyNoInteractions(notifications);

        var executed = chat(owner, conversation, response, status().isOk());

        assertChatResolution(executed, conversation, "EJECUTADA", id);
        var persisted = service.obtener(id);
        assertThat(persisted.estado()).isEqualTo(EstadoConfirmacion.EJECUTADA);
        assertThat(mapper.treeToValue(executed.path("operacion"), OperacionResumen.class)).isEqualTo(persisted.resultado());
        assertThat(executed.path("message").asText()).isEqualTo(persisted.resultado().detalle());
        Long resource = persisted.resultado().id();
        switch (type) {
            case CREAR_RUTA -> assertThat(routes.findById(resource).orElseThrow().getOrigen()).isEqualTo("Surco");
            case CREAR_VIAJE -> assertThat(trips.findById(resource).orElseThrow().getFecha()).isEqualTo(tripRequest().fecha());
            case SOLICITAR_UNIRSE -> assertThat(requests.findById(resource).orElseThrow().getEstado())
                    .isEqualTo(EstadoSolicitud.PENDIENTE);
            case REGISTRAR_METODO -> assertMetodo(resource, TipoCompensacion.FAVOR, "Compartir apuntes", true);
            case ACTUALIZAR_METODO -> assertMetodo(original.getId(), TipoCompensacion.DINERO, "Yape personal", true);
            case CAMBIAR_ESTADO_METODO -> assertMetodo(original.getId(), TipoCompensacion.DINERO, "Yape", false);
            case ELIMINAR_METODO -> assertThat(methods.existsById(original.getId())).isFalse();
            case EDITAR_RUTA -> assertThat(routes.findById(resource).orElseThrow().getCapacidadMaxima()).isEqualTo(5);
            case ELIMINAR_RUTA -> assertThat(routes.findById(resource).orElseThrow().getEstado())
                    .isEqualTo(EstadoRuta.ELIMINADA);
            case REPROGRAMAR_VIAJE -> assertThat(trips.findById(resource).orElseThrow().getFecha())
                    .isEqualTo(tripRequest().fecha().plusDays(1));
            case CANCELAR_SOLICITUD -> assertThat(requests.findById(persisted.parametros().get(0).asLong())
                    .orElseThrow().getEstado()).isEqualTo(EstadoSolicitud.CANCELADA);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"no", "No, gracias", "cancelar"})
    void cancelsInChatAndPreventsConfirmationFromEitherChannel(String response) throws Exception {
        var prepared = service.prepararRuta(routeRequest());
        UUID id = prepared.confirmacion().id();
        UUID conversation = bindProposal(driver, prepared);
        long before = routes.count();

        assertChatResolution(chat(driver, conversation, response, status().isOk()), conversation, "CANCELADA", id);
        assertChatResolution(chat(driver, conversation, "sí", status().isOk()), conversation, "YA_CANCELADA", id);
        assertChatResolution(chat(driver, conversation, "cancelar", status().isOk()), conversation, "YA_CANCELADA", id);
        confirmHttp(driver, id, status().isConflict());

        assertThat(service.obtener(id).estado()).isEqualTo(EstadoConfirmacion.CANCELADA);
        assertThat(routes.count()).isEqualTo(before);
        verifyNoInteractions(notifications);
    }

    @Test void observesAnHttpCancellationWhenTheConversationStillHasTheProposal() throws Exception {
        var prepared = service.prepararRuta(routeRequest());
        UUID id = prepared.confirmacion().id();
        UUID conversation = bindProposal(driver, prepared);
        long before = routes.count();

        var cancelled = callWithJwt(driver, post("/api/agent/confirmaciones/{id}/cancelar", id).contextPath("/api"),
                status().isOk());
        assertThat(cancelled.path("estado").asText()).isEqualTo("CANCELADA");
        var followUp = chat(driver, conversation, "confirmo", status().isOk());

        assertChatResolution(followUp, conversation, "YA_CANCELADA", id);
        assertThat(followUp.path("resolucionConfirmacion").path("confirmacion")).isEqualTo(cancelled);
        assertThat(routes.count()).isEqualTo(before);
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void replaysTheCommittedReceiptAcrossChatAndHttpWithoutAnotherWrite(boolean chatFirst) throws Exception {
        var prepared = service.prepararRuta(routeRequest());
        UUID id = prepared.confirmacion().id();
        UUID conversation = bindProposal(driver, prepared);
        long before = routes.count();
        JsonNode executed;
        if (chatFirst) {
            var chat = chat(driver, conversation, "sí", status().isOk());
            assertChatResolution(chat, conversation, "EJECUTADA", id);
            executed = chat.path("resolucionConfirmacion").path("confirmacion");
        } else {
            executed = confirmHttp(driver, id, status().isOk());
        }
        var replay = chat(driver, conversation, "sí", status().isOk());
        assertChatResolution(replay, conversation, "YA_EJECUTADA", id);
        assertThat(replay.path("resolucionConfirmacion").path("confirmacion")).isEqualTo(executed);
        assertThat(confirmHttp(driver, id, status().isOk())).isEqualTo(executed);
        assertThat(routes.count()).isEqualTo(before + 1);
        verify(notifications, times(1)).notificar(any(), any(), anyString(), anyString());
    }

    @Test void expiresInChatWithoutExecutingTheOperation() throws Exception {
        var prepared = service.prepararRuta(routeRequest());
        UUID id = prepared.confirmacion().id();
        UUID conversation = bindProposal(driver, prepared);
        var entity = confirmations.findById(id).orElseThrow();
        entity.setCreada(clock.instant().minusSeconds(1200));
        entity.setExpira(clock.instant().minusSeconds(1));
        confirmations.save(entity);
        long before = routes.count();

        assertChatResolution(chat(driver, conversation, "sí", status().isOk()), conversation, "EXPIRADA", id);
        assertThat(confirmHttp(driver, id, status().isGone()).path("estado").asText()).isEqualTo("EXPIRADA");
        assertThat(routes.count()).isEqualTo(before);
        verifyNoInteractions(notifications);
    }

    @Test void aDecisionRequiresJwtAndTheExactOwnedConversation() throws Exception {
        var prepared = service.prepararRuta(routeRequest());
        UUID id = prepared.confirmacion().id();
        UUID conversation = bindProposal(driver, prepared);
        UUID otherConversation;
        try (var other = conversations.open(driver.getId(), driver.getRolPrincipal(), null)) {
            otherConversation = other.id();
        }
        long before = routes.count();

        chat(null, conversation, "sí", status().isForbidden());
        chat(passenger, conversation, "sí", status().isNotFound());
        assertChatResolution(chat(driver, otherConversation, "sí", status().isOk()), otherConversation, "SIN_PENDIENTE", null);
        assertThat(chat(driver, null, "no", status().isOk()).path("resolucionConfirmacion").path("estado").asText())
                .isEqualTo("SIN_PENDIENTE");

        assertThat(service.obtener(id).estado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        assertThat(routes.count()).isEqualTo(before);
        verifyNoInteractions(notifications);
    }

    @Test void chatDecisionRejectsAConversationFromThePreviousRole() throws Exception {
        var prepared = service.prepararRuta(routeRequest());
        UUID conversation = bindProposal(driver, prepared);
        driver.setRolPrincipal(Rol.PASAJERO);
        users.save(driver);
        long before = routes.count();

        chat(driver, conversation, "sí", status().isConflict());

        assertThat(service.obtener(prepared.confirmacion().id()).estado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        assertThat(routes.count()).isEqualTo(before);
        verifyNoInteractions(notifications);
    }

    @Test void ambiguousConsentAndAChangeOfSubjectCannotExecuteThePendingOperation() throws Exception {
        var prepared = service.prepararRuta(routeRequest());
        UUID id = prepared.confirmacion().id();
        UUID conversation = bindProposal(driver, prepared);
        long before = routes.count();

        var clarification = chat(driver, conversation, "sí, pero cambia la fecha", status().isOk());
        assertThat(clarification.path("resolucionConfirmacion").path("estado").asText())
                .isEqualTo("ACLARACION_REQUERIDA");
        assertThat(clarification.path("operacion").isNull()).isTrue();
        var otherTopic = chat(driver, conversation, "¿Qué tipos de compensación existen?", status().isOk());
        assertThat(otherTopic.path("message").asText()).contains("DINERO", "GASOLINA", "FAVOR");
        assertChatResolution(chat(driver, conversation, "sí", status().isOk()), conversation, "SIN_PENDIENTE", null);

        assertThat(service.obtener(id).estado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        assertThat(routes.count()).isEqualTo(before);
        verifyNoInteractions(notifications);
    }

    @Test void theNextPreparedOperationReplacesAnExecutedReference() throws Exception {
        var first = service.prepararRuta(routeRequest());
        UUID conversation = bindProposal(driver, first);
        UUID firstId = first.confirmacion().id();
        long before = routes.count();
        assertChatResolution(chat(driver, conversation, "sí", status().isOk()), conversation, "EJECUTADA", firstId);

        var second = service.prepararRuta(new RutaRequest("Miraflores", 3, "Parque", null, null));
        UUID secondId = second.confirmacion().id();
        AgentConversationFixture.recordProposal(conversations, driver, conversation, second);
        var executed = chat(driver, conversation, "sí", status().isOk());

        assertChatResolution(executed, conversation, "EJECUTADA", secondId);
        assertThat(executed.path("message").asText()).contains("Miraflores");
        assertThat(service.obtener(firstId).estado()).isEqualTo(EstadoConfirmacion.EJECUTADA);
        assertThat(routes.count()).isEqualTo(before + 2);
        verify(notifications, times(2)).notificar(any(), any(), anyString(), anyString());
    }

    @Test void concurrentChatAndHttpDecisionsExecuteOnceWithOneCommittedReceipt() throws Exception {
        var prepared = service.prepararRuta(routeRequest());
        UUID id = prepared.confirmacion().id();
        UUID conversation = bindProposal(driver, prepared);
        // Build MockMvc on the calling thread before sharing it across requests.
        mvc();
        long before = routes.count();
        var chatWrote = new CountDownLatch(1);
        var httpStarted = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        doAnswer(invocation -> {
            chatWrote.countDown();
            await(commit);
            return null;
        }).when(notifications).notificar(any(), any(), anyString(), anyString());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var viaChat = pool.submit(() -> chat(driver, conversation, "sí", status().isOk()));
            try {
                await(chatWrote);
                var viaHttp = pool.submit(() -> {
                    httpStarted.countDown();
                    return confirmHttp(driver, id, status().isOk());
                });
                await(httpStarted);
                assertThatThrownBy(() -> viaHttp.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                commit.countDown();

                var chatResponse = viaChat.get(10, TimeUnit.SECONDS);
                assertChatResolution(chatResponse, conversation, "EJECUTADA", id);
                assertThat(chatResponse.path("resolucionConfirmacion").path("confirmacion"))
                        .isEqualTo(viaHttp.get(10, TimeUnit.SECONDS));
            } finally {
                commit.countDown();
            }
        }

        assertThat(routes.count()).isEqualTo(before + 1);
        verify(notifications, times(1)).notificar(any(), any(), anyString(), anyString());
    }

    @Test void rejectsAnObsoleteSnapshotInChatWithoutWriting() throws Exception {
        var method = metodo(driver, TipoCompensacion.DINERO, "Yape", true);
        var prepared = service.prepararEliminacionMetodo(null, "Yape");
        UUID id = prepared.confirmacion().id();
        UUID conversation = bindProposal(driver, prepared);
        methodService.actualizar(method.getId(), new MetodoCompensacionRequest(TipoCompensacion.DINERO, "Yape REST", true));

        var rejected = chat(driver, conversation, "sí", status().isOk());

        assertChatResolution(rejected, conversation, "RECHAZADA", null);
        assertThat(rejected.path("message").asText()).contains("cambiaron");
        assertThat(rejected.path("advertencia").path("codigo").asText()).isEqualTo("CONFIRMATION_BUSINESS_CONFLICT");
        assertThat(service.obtener(id).estado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        assertMetodo(method.getId(), TipoCompensacion.DINERO, "Yape REST", true);
        verify(notifications, times(1)).notificar(any(), any(), anyString(), anyString());
    }

    @Test void rejectsALateCapacityConflictInChatWithoutSendingARequest() throws Exception {
        Viaje trip = trip();
        authenticate(passenger);
        var prepared = service.prepararSolicitud(trip.getId(), new SolicitudRequest(null, TipoCompensacion.DINERO, null));
        UUID id = prepared.confirmacion().id();
        UUID conversation = bindProposal(passenger, prepared);
        trip.setPasajeros(4);
        trips.save(trip);
        long before = requests.count();

        var rejected = chat(passenger, conversation, "sí", status().isOk());

        assertChatResolution(rejected, conversation, "RECHAZADA", null);
        assertThat(rejected.path("message").asText()).contains("cupos");
        assertThat(service.obtener(id).estado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        assertThat(requests.count()).isEqualTo(before);
        verifyNoInteractions(notifications);
    }

    @Test void rejectsAResourceThatDisappearedAfterPreparation() throws Exception {
        Ruta ruta = route();
        var prepared = service.prepararViaje(ruta.getId(), tripRequest());
        UUID conversation = bindProposal(driver, prepared);
        routes.deleteById(ruta.getId());
        long before = trips.count();

        var rejected = chat(driver, conversation, "sí", status().isOk());

        assertChatResolution(rejected, conversation, "RECHAZADA", null);
        assertThat(rejected.path("advertencia").path("codigo").asText()).isEqualTo("CONFIRMATION_NOT_FOUND");
        assertThat(service.obtener(prepared.confirmacion().id()).estado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        assertThat(trips.count()).isEqualTo(before);
        verifyNoInteractions(notifications);
    }

    @Test void validatesThePreparedParametersAgainBeforeExecutionInChat() throws Exception {
        var prepared = service.prepararRuta(routeRequest());
        UUID id = prepared.confirmacion().id();
        UUID conversation = bindProposal(driver, prepared);
        var persisted = confirmations.findById(id).orElseThrow();
        persisted.setParametros(mapper.writeValueAsString(new RutaRequest("", 0, "", null, null)));
        confirmations.save(persisted);
        long before = routes.count();

        var rejected = chat(driver, conversation, "sí", status().isOk());

        assertChatResolution(rejected, conversation, "RECHAZADA", null);
        assertThat(rejected.path("advertencia").path("codigo").asText()).isEqualTo("CONFIRMATION_INVALID");
        assertThat(service.obtener(id).estado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        assertThat(routes.count()).isEqualTo(before);
        verifyNoInteractions(notifications);
    }

    @Test void preparesRouteWithoutBusinessWritesAndKeepsExactParameters() {
        long before = routes.count();
        var result = service.prepararRuta(routeRequest());

        assertThat(result.estado()).isEqualTo(EstadoOperacion.CONFIRMACION_REQUERIDA);
        assertThat(result.id()).isNull();
        assertThat(result.confirmacion().id()).isNotNull();
        assertThat(result.confirmacion().estado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        assertThat(result.confirmacion().resumen()).contains("Surco", "UPC", "capacidad 4", "Parque");
        assertThat(result.confirmacion().parametros().path("origen").asText()).isEqualTo("Surco");
        assertThat(result.confirmacion().parametros().path("capacidadMaxima").asInt()).isEqualTo(4);
        assertThat(result.confirmacion().expira()).isAfter(clock.instant().plusSeconds(500));
        assertThat(routes.count()).isEqualTo(before);
        verifyNoInteractions(notifications);
    }

    @Test void confirmsOnceAndReplayReturnsSameCommittedReceipt() {
        var pending = service.prepararRuta(routeRequest()).confirmacion();
        long before = routes.count();

        var executed = service.confirmar(pending.id());
        var replay = service.confirmar(pending.id());

        assertThat(executed.estado()).isEqualTo(EstadoConfirmacion.EJECUTADA);
        assertThat(executed.resultado().estado()).isEqualTo(EstadoOperacion.EXITO);
        assertThat(replay).isEqualTo(executed);
        assertThat(routes.count()).isEqualTo(before + 1);
        verify(notifications, times(1)).notificar(any(), any(), anyString(), anyString());
    }

    @Test void preparesAndExecutesTripUsingAnOwnedRoute() {
        Ruta route = route();
        var request = tripRequest();
        long before = trips.count();
        var pending = service.prepararViaje(route.getId(), request).confirmacion();
        assertThat(trips.count()).isEqualTo(before);
        assertThat(pending.resumen()).contains(request.fecha().toString(), request.hora().toString(), "UPC", "Surco");

        var executed = service.confirmar(pending.id());

        assertThat(executed.estado()).isEqualTo(EstadoConfirmacion.EJECUTADA);
        assertThat(trips.count()).isEqualTo(before + 1);
        assertThat(trips.findById(executed.resultado().id()).orElseThrow().getFecha()).isEqualTo(request.fecha());
    }

    @Test void preparesAndExecutesPassengerRequestWithoutChangingCapacity() {
        Viaje trip = trip();
        authenticate(passenger);
        long before = requests.count();
        var pending = service.prepararSolicitud(trip.getId(), new SolicitudRequest(null, TipoCompensacion.GASOLINA, null)).confirmacion();
        assertThat(requests.count()).isEqualTo(before);
        assertThat(pending.resumen()).contains("GASOLINA", "PENDIENTE");

        var executed = service.confirmar(pending.id());

        assertThat(executed.resultado().estado()).isEqualTo(EstadoOperacion.EXITO);
        assertThat(requests.count()).isEqualTo(before + 1);
        assertThat(requests.findById(executed.resultado().id()).orElseThrow().getEstado()).isEqualTo(EstadoSolicitud.PENDIENTE);
        assertThat(trips.findById(trip.getId()).orElseThrow().getPasajeros()).isZero();
    }

    @Test void preparesAndExecutesCompensationMethodForPassenger() {
        authenticate(passenger);
        long before = methods.count();
        var pending = service.prepararMetodo(new MetodoCompensacionRequest(TipoCompensacion.FAVOR, "Compartir apuntes", true)).confirmacion();
        assertThat(methods.count()).isEqualTo(before);

        var executed = service.confirmar(pending.id());

        assertThat(methods.count()).isEqualTo(before + 1);
        assertThat(executed.resultado().estado()).isEqualTo(EstadoOperacion.EXITO);
    }

    @ParameterizedTest
    @EnumSource(Rol.class)
    void editsOnlyDescriptionAndPersistsExactSnapshotForEitherRole(Rol role) {
        Usuario owner = role == Rol.CONDUCTOR ? driver : passenger;
        authenticate(owner);
        var original = metodo(owner, TipoCompensacion.DINERO, "Yape", false);
        long before = methods.count();

        var proposal = service.prepararActualizacionMetodo(null, " yApE ", null, " Yape personal ");

        assertThat(proposal.estado()).isEqualTo(EstadoOperacion.CONFIRMACION_REQUERIDA);
        assertThat(proposal.id()).isNull();
        var pending = proposal.confirmacion();
        assertThat(pending.tipo()).isEqualTo(TipoOperacion.ACTUALIZAR_METODO);
        assertThat(pending.estado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        assertThat(pending.resumen()).contains("DINERO «Yape» (inactivo)", "DINERO «Yape personal» (inactivo)");
        assertThat(pending.parametros().path("tipo").asText()).isEqualTo("DINERO");
        assertThat(pending.parametros().path("descripcion").asText()).isEqualTo("Yape personal");
        assertThat(pending.parametros().path("activo").asBoolean(true)).isFalse();
        transaction.executeWithoutResult(status -> {
            var stored = confirmations.findById(pending.id()).orElseThrow();
            assertThat(stored.getUsuario().getId()).isEqualTo(owner.getId());
            assertThat(stored.getRecursoId()).isEqualTo(original.getId());
            var snapshot = mapper.readTree(stored.getSnapshot());
            assertThat(snapshot.path("metodoId").asLong()).isEqualTo(original.getId());
            assertThat(snapshot.path("usuarioId").asLong()).isEqualTo(owner.getId());
            assertThat(snapshot.path("tipo").asText()).isEqualTo("DINERO");
            assertThat(snapshot.path("descripcion").asText()).isEqualTo("Yape");
            assertThat(snapshot.path("activo").asBoolean(true)).isFalse();
        });
        assertThat(methods.count()).isEqualTo(before);
        assertMetodo(original.getId(), TipoCompensacion.DINERO, "Yape", false);
        verifyNoInteractions(notifications);

        var executed = service.confirmar(pending.id());
        assertThat(executed.estado()).isEqualTo(EstadoConfirmacion.EJECUTADA);
        assertThat(executed.resultado().id()).isEqualTo(original.getId());
        assertThat(service.confirmar(pending.id())).isEqualTo(executed);
        assertMetodo(original.getId(), TipoCompensacion.DINERO, "Yape personal", false);
        verify(notifications).notificar(any(), any(), anyString(), anyString());
    }

    @Test void editsOnlyTypeAndRejectsDuplicatesThatAppearBeforeConfirmation() {
        var original = metodo(driver, TipoCompensacion.FAVOR, "Apuntes", false);
        var proposal = service.prepararActualizacionMetodo(TipoCompensacion.FAVOR, "Apuntes",
                TipoCompensacion.DINERO, null);
        UUID id = proposal.confirmacion().id();
        var duplicate = metodo(driver, TipoCompensacion.DINERO, "APUNTES", true);

        assertThatThrownBy(() -> service.confirmar(id))
                .isInstanceOf(BusinessException.class).hasMessageContaining("mismo tipo y descripción");
        assertThatThrownBy(() -> service.prepararActualizacionMetodo(TipoCompensacion.FAVOR, "Apuntes",
                TipoCompensacion.DINERO, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("mismo tipo y descripción");
        assertThat(service.obtener(id).estado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        assertMetodo(original.getId(), TipoCompensacion.FAVOR, "Apuntes", false);
        verifyNoInteractions(notifications);

        methods.deleteById(duplicate.getId());
        assertThat(service.confirmar(id).estado()).isEqualTo(EstadoConfirmacion.EJECUTADA);
        assertMetodo(original.getId(), TipoCompensacion.DINERO, "Apuntes", false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void changesOnlyActiveStateAndDoesNotPrepareANoop(boolean active) {
        var original = metodo(driver, TipoCompensacion.GASOLINA, "Combustible", !active);
        var proposal = service.prepararEstadoMetodo(TipoCompensacion.GASOLINA, null, active);
        assertThat(proposal.confirmacion().tipo()).isEqualTo(TipoOperacion.CAMBIAR_ESTADO_METODO);
        assertThat(proposal.confirmacion().parametros().path("activo").asBoolean()).isEqualTo(active);
        assertMetodo(original.getId(), TipoCompensacion.GASOLINA, "Combustible", !active);
        verifyNoInteractions(notifications);

        service.confirmar(proposal.confirmacion().id());
        assertMetodo(original.getId(), TipoCompensacion.GASOLINA, "Combustible", active);
        long before = confirmations.count();
        assertThat(service.prepararEstadoMetodo(TipoCompensacion.GASOLINA, null, active).estado())
                .isEqualTo(EstadoOperacion.SIN_CAMBIOS);
        assertThat(service.prepararActualizacionMetodo(null, "Combustible", null, "Combustible").estado())
                .isEqualTo(EstadoOperacion.SIN_CAMBIOS);
        assertThat(confirmations.count()).isEqualTo(before);
        verify(notifications).notificar(any(), any(), anyString(), anyString());
    }

    @Test void looksUpOnlyOwnedMethodsAcrossPagesAndReportsAmbiguity() {
        var original = metodo(driver, TipoCompensacion.DINERO, "Yape 100%_literal", true);
        for (int i = 0; i < 15; i++) metodo(driver, TipoCompensacion.GASOLINA, "Combustible " + i, true);
        metodo(passenger, TipoCompensacion.DINERO, "Yape 100%_literal", true);
        metodo(passenger, TipoCompensacion.FAVOR, "Solo ajeno", true);

        long before = confirmations.count();
        assertThat(service.prepararEliminacionMetodo(null, "Solo ajeno").estado())
                .isEqualTo(EstadoOperacion.NO_ENCONTRADO);
        assertThat(service.prepararEliminacionMetodo(TipoCompensacion.GASOLINA, null).estado())
                .isEqualTo(EstadoOperacion.AMBIGUO);
        assertThat(confirmations.count()).isEqualTo(before);

        metodo(driver, TipoCompensacion.DINERO, "Yape 100XXliteral", true);
        var pending = service.prepararEliminacionMetodo(null, "100%_").confirmacion();
        assertThat(pending.tipo()).isEqualTo(TipoOperacion.ELIMINAR_METODO);
        assertThat(confirmations.findById(pending.id()).orElseThrow().getRecursoId()).isEqualTo(original.getId());
        assertThat(methods.findById(original.getId())).isPresent();
        verifyNoInteractions(notifications);

        authenticate(passenger);
        assertThatThrownBy(() -> service.obtener(pending.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.cancelar(pending.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.confirmar(pending.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThat(methods.findById(original.getId())).isPresent();
    }

    @Test void deletesOnlyAfterConfirmationAndReturnsTheCommittedReceiptOnReplay() {
        var original = metodo(driver, TipoCompensacion.FAVOR, "Favor con apuntes", true);
        var pending = service.prepararEliminacionMetodo(null, "Favor con apuntes").confirmacion();
        assertThat(pending.resumen()).contains("FAVOR", "Favor con apuntes");
        assertThat(methods.findById(original.getId())).isPresent();
        verifyNoInteractions(notifications);

        var executed = service.confirmar(pending.id());

        assertThat(executed.estado()).isEqualTo(EstadoConfirmacion.EJECUTADA);
        assertThat(executed.resultado().estado()).isEqualTo(EstadoOperacion.EXITO);
        assertThat(methods.findById(original.getId())).isEmpty();
        assertThat(service.confirmar(pending.id())).isEqualTo(executed);
        verify(notifications).notificar(any(), any(), anyString(), anyString());
    }

    @ParameterizedTest
    @EnumSource(EstadoSolicitud.class)
    void anyLateRequestAssociationPreventsDeletionAndKeepsConfirmationPending(EstadoSolicitud status) {
        var original = metodo(passenger, TipoCompensacion.FAVOR, "Favor", true);
        var trip = trip();
        authenticate(passenger);
        UUID id = service.prepararEliminacionMetodo(null, "Favor").confirmacion().id();
        requests.save(Solicitud.builder().viaje(trip).pasajero(passenger).estado(status)
                .preferenciaCompensacion(TipoCompensacion.FAVOR).metodoCompensacion(original).build());

        assertThatThrownBy(() -> service.prepararEliminacionMetodo(null, "Favor"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("solicitudes asociadas", "desactivarlo");
        assertThatThrownBy(() -> service.confirmar(id))
                .isInstanceOf(BusinessException.class).hasMessageContaining("solicitudes asociadas");
        assertThat(service.obtener(id).estado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        assertMetodo(original.getId(), TipoCompensacion.FAVOR, "Favor", true);
        verifyNoInteractions(notifications);
    }

    @Test void cancelledAndExpiredMethodConfirmationsLeaveTheMethodIntact() {
        var original = metodo(driver, TipoCompensacion.DINERO, "Efectivo", true);
        UUID cancel = service.prepararEstadoMetodo(null, "Efectivo", false).confirmacion().id();
        UUID expire = service.prepararEliminacionMetodo(null, "Efectivo").confirmacion().id();
        assertThat(service.cancelar(cancel).estado()).isEqualTo(EstadoConfirmacion.CANCELADA);
        var expired = confirmations.findById(expire).orElseThrow();
        expired.setCreada(clock.instant().minusSeconds(1200));
        expired.setExpira(clock.instant().minusSeconds(1));
        confirmations.save(expired);

        assertThatThrownBy(() -> service.confirmar(cancel)).isInstanceOf(BusinessException.class).hasMessageContaining("cancelada");
        assertThat(service.confirmar(expire).estado()).isEqualTo(EstadoConfirmacion.EXPIRADA);
        assertMetodo(original.getId(), TipoCompensacion.DINERO, "Efectivo", true);
        verifyNoInteractions(notifications);
    }

    @Test void methodConfirmationRevalidatesTheActiveRoleBeforeTakingEffect() {
        var original = metodo(driver, TipoCompensacion.DINERO, "Efectivo", true);
        UUID pending = service.prepararEstadoMetodo(null, "Efectivo", false).confirmacion().id();
        driver.setRolPrincipal(null);
        users.save(driver);

        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(NoPermitidoException.class);
        assertMetodo(original.getId(), TipoCompensacion.DINERO, "Efectivo", true);
        assertThat(service.obtener(pending).estado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        verifyNoInteractions(notifications);

        driver.setRolPrincipal(Rol.PASAJERO);
        users.save(driver);
        assertThat(service.confirmar(pending).estado()).isEqualTo(EstadoConfirmacion.EJECUTADA);
        assertMetodo(original.getId(), TipoCompensacion.DINERO, "Efectivo", false);
        verify(notifications).notificar(any(), any(), anyString(), anyString());
    }

    @Test void failureDuringMethodUpdateRollsBackBothTheChangeAndItsReceipt() {
        var original = metodo(driver, TipoCompensacion.DINERO, "Efectivo", true);
        UUID id = service.prepararActualizacionMetodo(null, "Efectivo", null, "Pago en efectivo").confirmacion().id();
        doThrow(new BusinessException("Fallo transaccional de prueba"))
                .when(notifications).notificar(any(), any(), anyString(), anyString());

        assertThatThrownBy(() -> service.confirmar(id)).isInstanceOf(BusinessException.class);

        assertMetodo(original.getId(), TipoCompensacion.DINERO, "Efectivo", true);
        var pending = service.obtener(id);
        assertThat(pending.estado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        assertThat(pending.resultado()).isNull();
        reset(notifications);
        var executed = service.confirmar(id);
        assertThat(service.confirmar(id)).isEqualTo(executed);
        assertMetodo(original.getId(), TipoCompensacion.DINERO, "Pago en efectivo", true);
        verify(notifications).notificar(any(), any(), anyString(), anyString());
    }

    @Test void passengerCannotPrepareDriverOperations() {
        authenticate(passenger);
        assertThatThrownBy(() -> service.prepararRuta(routeRequest())).isInstanceOf(NoPermitidoException.class);
        assertThatThrownBy(() -> service.prepararViaje(123L, tripRequest())).isInstanceOf(NoPermitidoException.class);
        verifyNoInteractions(notifications);
    }

    @Test void driverCannotPreparePassengerOperation() {
        assertThatThrownBy(() -> service.prepararSolicitud(123L,
                new SolicitudRequest(null, TipoCompensacion.DINERO, null))).isInstanceOf(NoPermitidoException.class);
    }

    @Test void unauthenticatedPreparationAndExecutionAreRejected() {
        UUID pending = service.prepararRuta(routeRequest()).confirmacion().id();
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.prepararRuta(routeRequest())).isInstanceOf(NoAutorizadoException.class);
        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(NoAutorizadoException.class);
    }

    @Test void anotherUserCannotReadCancelOrExecuteConfirmation() {
        UUID pending = service.prepararRuta(routeRequest()).confirmacion().id();
        authenticate(passenger);
        assertThatThrownBy(() -> service.obtener(pending)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.cancelar(pending)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(confirmations.findById(pending).orElseThrow().getEstado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
    }

    @Test void routeOfAnotherDriverCannotBePrepared() {
        Ruta other = route();
        authenticate(user(Rol.CONDUCTOR));
        assertThatThrownBy(() -> service.prepararViaje(other.getId(), tripRequest())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void roleChangeBeforeExecutionRevokesPermissionAndDoesNotWrite() {
        UUID pending = service.prepararRuta(routeRequest()).confirmacion().id();
        long before = routes.count();
        driver.setRolPrincipal(Rol.PASAJERO);
        users.save(driver);
        // The SecurityContext still carries the old authority; DB role remains authoritative.
        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(NoPermitidoException.class);
        assertThat(routes.count()).isEqualTo(before);
        assertThat(confirmations.findById(pending).orElseThrow().getEstado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
    }

    @Test void expiredConfirmationPersistsExpiredStateWithoutExecution() {
        UUID pending = service.prepararRuta(routeRequest()).confirmacion().id();
        var entity = confirmations.findById(pending).orElseThrow();
        entity.setCreada(clock.instant().minusSeconds(1200));
        entity.setExpira(clock.instant().minusSeconds(1));
        confirmations.save(entity);
        long before = routes.count();

        assertThat(service.confirmar(pending).estado()).isEqualTo(EstadoConfirmacion.EXPIRADA);
        assertThat(confirmations.findById(pending).orElseThrow().getEstado()).isEqualTo(EstadoConfirmacion.EXPIRADA);
        assertThat(routes.count()).isEqualTo(before);
        verifyNoInteractions(notifications);
    }

    @Test void cancelledConfirmationCannotExecuteAndCancellationIsIdempotent() {
        UUID pending = service.prepararRuta(routeRequest()).confirmacion().id();
        assertThat(service.cancelar(pending).estado()).isEqualTo(EstadoConfirmacion.CANCELADA);
        assertThat(service.cancelar(pending).estado()).isEqualTo(EstadoConfirmacion.CANCELADA);
        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(BusinessException.class).hasMessageContaining("cancelada");
        verifyNoInteractions(notifications);
    }

    @Test void executedConfirmationCannotBeCancelled() {
        UUID pending = service.prepararRuta(routeRequest()).confirmacion().id();
        service.confirmar(pending);
        assertThatThrownBy(() -> service.cancelar(pending)).isInstanceOf(BusinessException.class).hasMessageContaining("ejecutada");
    }

    @Test void profileDestinationChangeRequiresNewPreparation() {
        UUID pending = service.prepararRuta(routeRequest()).confirmacion().id();
        Perfil profile = profiles.findByUsuarioId(driver.getId()).orElseThrow();
        profile.setUniversidad("Otra sede"); profiles.save(profile);
        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(BusinessException.class).hasMessageContaining("cambiaron");
        assertThat(confirmations.findById(pending).orElseThrow().getEstado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        verifyNoInteractions(notifications);
    }

    @Test void routeDetailsChangedAfterPreparationCannotBeSilentlyUsed() {
        Ruta route = route();
        UUID pending = service.prepararViaje(route.getId(), tripRequest()).confirmacion().id();
        route.setOrigen("Miraflores"); routes.save(route);
        long before = trips.count();
        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(BusinessException.class).hasMessageContaining("cambiaron");
        assertThat(trips.count()).isEqualTo(before);
        verifyNoInteractions(notifications);
    }

    @Test void tripScheduleChangedAfterPreparationCannotBeSilentlyUsed() {
        Viaje trip = trip(); authenticate(passenger);
        UUID pending = service.prepararSolicitud(trip.getId(), new SolicitudRequest(null, TipoCompensacion.DINERO, null)).confirmacion().id();
        trip.setHora(trip.getHora().plusMinutes(30)); trips.save(trip);
        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(BusinessException.class).hasMessageContaining("cambiaron");
        verifyNoInteractions(notifications);
    }

    @Test void businessRulesAreRevalidatedWhenCapacityWasConsumed() {
        Viaje trip = trip(); authenticate(passenger);
        UUID pending = service.prepararSolicitud(trip.getId(), new SolicitudRequest(null, TipoCompensacion.DINERO, null)).confirmacion().id();
        trip.setPasajeros(4); trips.save(trip);
        long before = requests.count();
        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(BusinessException.class).hasMessageContaining("cupos");
        assertThat(requests.count()).isEqualTo(before);
        assertThat(confirmations.findById(pending).orElseThrow().getEstado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
    }

    @Test void duplicateBusinessRequestIsRevalidatedWithoutSideEffectNotification() {
        Viaje trip = trip(); authenticate(passenger);
        UUID pending = service.prepararSolicitud(trip.getId(), new SolicitudRequest(null, TipoCompensacion.DINERO, null)).confirmacion().id();
        requests.save(Solicitud.builder().viaje(trip).pasajero(passenger)
                .preferenciaCompensacion(TipoCompensacion.DINERO).estado(EstadoSolicitud.PENDIENTE).build());
        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(BusinessException.class).hasMessageContaining("Ya enviaste");
        verifyNoInteractions(notifications);
    }

    @Test void validationFailureDoesNotCreatePendingOperation() {
        long before = confirmations.count();
        assertThatThrownBy(() -> service.prepararRuta(new RutaRequest("", 0, "", null, null)))
                .isInstanceOf(CamposInvalidosException.class);
        assertThat(confirmations.count()).isEqualTo(before);
    }

    @Test void transactionRollbackKeepsPendingAndAllowsExplicitSafeRetry() {
        UUID pending = service.prepararRuta(routeRequest()).confirmacion().id();
        long before = routes.count();
        doThrow(new BusinessException("Fallo de prueba posterior a guardar ruta"))
                .when(notifications).notificar(any(), any(), anyString(), anyString());
        assertThatThrownBy(() -> service.confirmar(pending)).isInstanceOf(BusinessException.class);
        assertThat(routes.count()).isEqualTo(before);
        assertThat(confirmations.findById(pending).orElseThrow().getEstado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        assertThat(confirmations.findById(pending).orElseThrow().getResultado()).isNull();

        reset(notifications);
        assertThat(service.confirmar(pending).estado()).isEqualTo(EstadoConfirmacion.EJECUTADA);
        assertThat(service.confirmar(pending).estado()).isEqualTo(EstadoConfirmacion.EJECUTADA);
        assertThat(routes.count()).isEqualTo(before + 1);
    }

    @Test void simultaneousConfirmationsExecuteExactlyOnce() throws Exception {
        UUID pending = service.prepararRuta(routeRequest()).confirmacion().id();
        long before = routes.count();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<ConfirmacionResumen> confirm = () -> {
                authenticate(driver);
                try { start.await(); return service.confirmar(pending); }
                finally { SecurityContextHolder.clearContext(); }
            };
            Future<ConfirmacionResumen> first = pool.submit(confirm);
            Future<ConfirmacionResumen> second = pool.submit(confirm);
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(routes.count()).isEqualTo(before + 1);
        verify(notifications, times(1)).notificar(any(), any(), anyString(), anyString());
    }

    @Test void pendingCountIsBoundedPerUser() {
        for (int i = 0; i < 20; i++) service.prepararRuta(routeRequest());
        assertThatThrownBy(() -> service.prepararRuta(routeRequest())).isInstanceOf(BusinessException.class).hasMessageContaining("20");
    }

    @Test void differentConfirmationsCannotRegisterTheSameMethodConcurrently() throws Exception {
        var request = new MetodoCompensacionRequest(TipoCompensacion.FAVOR, "Compartir apuntes", true);
        UUID firstId = service.prepararMetodo(request).confirmacion().id();
        UUID secondId = service.prepararMetodo(request).confirmacion().id();
        long before = methods.count();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.function.Function<UUID, Callable<String>> confirmation = id -> () -> {
                authenticate(driver);
                try {
                    start.await();
                    return service.confirmar(id).estado().name();
                } catch (BusinessException conflict) {
                    assertThat(conflict).hasMessageContaining("mismo tipo y descripción");
                    return "CONFLICTO";
                } finally { SecurityContextHolder.clearContext(); }
            };
            Future<String> first = pool.submit(confirmation.apply(firstId));
            Future<String> second = pool.submit(confirmation.apply(secondId));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("EJECUTADA", "CONFLICTO");
        }
        assertThat(methods.count()).isEqualTo(before + 1);
        assertThat(List.of(service.obtener(firstId).estado(), service.obtener(secondId).estado()))
                .containsExactlyInAnyOrder(EstadoConfirmacion.EJECUTADA, EstadoConfirmacion.PENDIENTE);
        verify(notifications, times(1)).notificar(any(), any(), anyString(), anyString());
    }

    @Test void concurrentMethodConfirmationsForOneUuidOnlyUpdateOnce() throws Exception {
        var original = metodo(driver, TipoCompensacion.DINERO, "Yape", true);
        UUID id = service.prepararEstadoMetodo(null, "Yape", false).confirmacion().id();
        var start = new CountDownLatch(1);
        Callable<ConfirmacionResumen> confirm = () -> {
            authenticate(driver);
            try {
                await(start);
                return service.confirmar(id);
            } finally {
                SecurityContextHolder.clearContext();
            }
        };

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(confirm);
            var second = pool.submit(confirm);
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }

        assertMetodo(original.getId(), TipoCompensacion.DINERO, "Yape", false);
        assertThat(service.obtener(id).estado()).isEqualTo(EstadoConfirmacion.EJECUTADA);
        verify(notifications).notificar(any(), any(), anyString(), anyString());
    }

    @Test void differentConfirmationsForTheSameMethodCannotSilentlyOverwriteOneAnother() throws Exception {
        var original = metodo(driver, TipoCompensacion.DINERO, "Yape", true);
        UUID edit = service.prepararActualizacionMetodo(null, "Yape", null, "Yape personal").confirmacion().id();
        UUID remove = service.prepararEliminacionMetodo(null, "Yape").confirmacion().id();

        confirmaConUnConflicto(edit, remove, "nueva");

        if (service.obtener(edit).estado() == EstadoConfirmacion.EJECUTADA) {
            assertMetodo(original.getId(), TipoCompensacion.DINERO, "Yape personal", true);
        } else {
            assertThat(methods.findById(original.getId())).isEmpty();
        }
    }

    @Test void differentMethodsOfOneOwnerCannotConcurrentlyAcquireTheSameTypeAndDescription() throws Exception {
        var first = metodo(driver, TipoCompensacion.DINERO, "Efectivo", true);
        var second = metodo(driver, TipoCompensacion.FAVOR, "Apuntes", true);
        UUID editFirst = service.prepararActualizacionMetodo(null, "Efectivo", TipoCompensacion.FAVOR, "Compartir")
                .confirmacion().id();
        UUID editSecond = service.prepararActualizacionMetodo(null, "Apuntes", null, "compartir")
                .confirmacion().id();

        confirmaConUnConflicto(editFirst, editSecond, "mismo tipo y descripción");

        if (service.obtener(editFirst).estado() == EstadoConfirmacion.EJECUTADA) {
            assertMetodo(first.getId(), TipoCompensacion.FAVOR, "Compartir", true);
            assertMetodo(second.getId(), TipoCompensacion.FAVOR, "Apuntes", true);
        } else {
            assertMetodo(first.getId(), TipoCompensacion.DINERO, "Efectivo", true);
            assertMetodo(second.getId(), TipoCompensacion.FAVOR, "compartir", true);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitsForATraditionalWriteAndThenRejectsTheStaleMethodSnapshot(boolean delete) throws Exception {
        var original = metodo(driver, TipoCompensacion.DINERO, "Yape", true);
        UUID pending = service.prepararEliminacionMetodo(null, "Yape").confirmacion().id();
        var written = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var confirmStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var writer = pool.submit(() -> {
                authenticate(driver);
                try {
                    transaction.executeWithoutResult(status -> {
                        if (delete) methodService.eliminar(original.getId());
                        else methodService.actualizar(original.getId(),
                                new MetodoCompensacionRequest(TipoCompensacion.DINERO, "Yape REST", true));
                        written.countDown();
                        await(commit);
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            try {
                await(written);
                var confirm = pool.submit(() -> {
                    authenticate(driver);
                    try {
                        confirmStarted.countDown();
                        return service.confirmar(pending);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
                await(confirmStarted);
                assertThatThrownBy(() -> confirm.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                commit.countDown();
                writer.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> confirm.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(BusinessException.class);
            } finally {
                commit.countDown();
            }
        }

        if (delete) assertThat(methods.findById(original.getId())).isEmpty();
        else assertMetodo(original.getId(), TipoCompensacion.DINERO, "Yape REST", true);
        assertThat(service.obtener(pending).estado()).isEqualTo(EstadoConfirmacion.PENDIENTE);
        verify(notifications).notificar(any(), any(), anyString(), anyString());
    }

    @Test void differentOwnersCanConfirmMethodsIndependently() throws Exception {
        var first = metodo(driver, TipoCompensacion.DINERO, "Yape", true);
        var second = metodo(passenger, TipoCompensacion.DINERO, "Yape", true);
        UUID driverPending = service.prepararEstadoMetodo(null, "Yape", false).confirmacion().id();
        authenticate(passenger);
        UUID passengerPending = service.prepararEstadoMetodo(null, "Yape", false).confirmacion().id();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var driverResult = pool.submit(() -> confirmarConcurrente(driver, driverPending, start, null));
            var passengerResult = pool.submit(() -> confirmarConcurrente(passenger, passengerPending, start, null));
            start.countDown();
            assertThat(driverResult.get(10, TimeUnit.SECONDS)).isEqualTo("EJECUTADA");
            assertThat(passengerResult.get(10, TimeUnit.SECONDS)).isEqualTo("EJECUTADA");
        }
        assertMetodo(first.getId(), TipoCompensacion.DINERO, "Yape", false);
        assertMetodo(second.getId(), TipoCompensacion.DINERO, "Yape", false);
        verify(notifications, times(2)).notificar(any(), any(), anyString(), anyString());
    }

    private void confirmaConUnConflicto(UUID firstId, UUID secondId, String message) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> confirmarConcurrente(driver, firstId, start, message));
            var second = pool.submit(() -> confirmarConcurrente(driver, secondId, start, message));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("EJECUTADA", "CONFLICTO");
        }
        assertThat(List.of(service.obtener(firstId).estado(), service.obtener(secondId).estado()))
                .containsExactlyInAnyOrder(EstadoConfirmacion.EJECUTADA, EstadoConfirmacion.PENDIENTE);
        verify(notifications).notificar(any(), any(), anyString(), anyString());
    }

    private UUID bindProposal(Usuario owner, OperacionResumen proposal) {
        return AgentConversationFixture.recordProposal(conversations, owner, null, proposal);
    }

    private MockMvc mvc() {
        if (mvc == null) mvc = MockMvcBuilders.webAppContextSetup(web).addFilters(securityChain).build();
        return mvc;
    }

    private JsonNode chat(Usuario user, UUID conversation, String message, ResultMatcher expected) throws Exception {
        return callWithJwt(user, post("/api/agent/chat").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(new ChatRequest(message, conversation))), expected);
    }

    private JsonNode confirmHttp(Usuario user, UUID id, ResultMatcher expected) throws Exception {
        return callWithJwt(user, post("/api/agent/confirmaciones/{id}/confirmar", id).contextPath("/api"), expected);
    }

    private JsonNode callWithJwt(Usuario user, MockHttpServletRequestBuilder request, ResultMatcher expected) throws Exception {
        if (user != null) request.header("Authorization", "Bearer " + jwt.generarToken(user.getCorreoInstitucional()));
        var priorIdentity = SecurityContextHolder.getContext();
        SecurityContextHolder.clearContext();
        try {
            var result = mvc().perform(request).andExpect(expected).andReturn().getResponse();
            return result.getContentAsByteArray().length == 0
                    ? mapper.createObjectNode() : mapper.readTree(result.getContentAsByteArray());
        } finally {
            SecurityContextHolder.setContext(priorIdentity);
        }
    }

    private void assertChatResolution(JsonNode response, UUID conversation, String state, UUID confirmation) {
        assertThat(response.path("conversationId").asText()).isEqualTo(conversation.toString());
        var resolution = response.path("resolucionConfirmacion");
        assertThat(resolution.path("estado").asText()).isEqualTo(state);
        if (confirmation != null) {
            assertThat(resolution.path("confirmacion").path("id").asText()).isEqualTo(confirmation.toString());
        }
        if (!state.equals("EJECUTADA")) assertThat(response.path("operacion").isNull()).isTrue();
    }

    private String confirmarConcurrente(Usuario owner, UUID id, CountDownLatch start, String conflictMessage) {
        authenticate(owner);
        try {
            await(start);
            return service.confirmar(id).estado().name();
        } catch (BusinessException conflict) {
            if (conflictMessage == null) throw conflict;
            assertThat(conflict).hasMessageContaining(conflictMessage);
            return "CONFLICTO";
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    static void await(CountDownLatch signal) {
        try {
            assertThat(signal.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    MetodoCompensacion metodo(Usuario owner, TipoCompensacion type, String description, boolean active) {
        return methods.save(MetodoCompensacion.builder().usuario(owner).tipo(type).descripcion(description)
                .activo(active).build());
    }

    private void assertMetodo(Long id, TipoCompensacion type, String description, boolean active) {
        assertThat(methods.findById(id)).hasValueSatisfying(method -> {
            assertThat(method.getTipo()).isEqualTo(type);
            assertThat(method.getDescripcion()).isEqualTo(description);
            assertThat(method.isActivo()).isEqualTo(active);
        });
    }

    private Usuario user(Rol role) {
        Usuario user = users.save(Usuario.builder().correoInstitucional(UUID.randomUUID() + "@upc.edu.pe")
                .telefono("fixture-" + UUID.randomUUID()).nombre("Ana").apellidos("Prueba")
                .contrasenaHash("isolated-test-hash").rolPrincipal(role).aceptaTerminos(true).cuentaVerificada(true).build());
        roles.asegurarPerfil(user, role);
        return user;
    }

    void authenticate(Usuario user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user.getCorreoInstitucional(), null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRolPrincipal()))));
    }

    private RutaRequest routeRequest() { return new RutaRequest("Surco", 4, "Parque", null, null); }
    private ViajeRequest tripRequest() {
        return new ViajeRequest(LocalDate.now(clock).plusDays(3), LocalTime.of(8, 0));
    }
    private Ruta route() {
        return routes.save(Ruta.builder().conductor(driver).origen("Surco").destino("UPC")
                .capacidadMaxima(4).puntosReferencia("Parque").estado(EstadoRuta.ACTIVA).build());
    }
    Viaje trip() {
        return trips.save(Viaje.builder().ruta(route()).fecha(tripRequest().fecha()).hora(tripRequest().hora())
                .dia("Lunes").estado(EstadoViaje.PROGRAMADO).build());
    }
}
