package com.uniride.agent;

import static com.uniride.agent.tools.ContribucionAgentTools.NOMBRE;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.uniride.agent.dto.ChatResponse;
import com.uniride.agent.dto.ContribucionFiltros;
import com.uniride.agent.dto.ContribucionResultado;
import com.uniride.agent.dto.ConfirmacionResumen;
import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.dto.OperacionResumen;
import com.uniride.agent.dto.ResolucionConfirmacion;
import com.uniride.agent.dto.ResultadoAgente;
import com.uniride.agent.dto.RutaResumen;
import com.uniride.agent.confirmation.*;
import com.uniride.agent.tools.ContribucionAgentTools;
import com.uniride.agent.tools.MetodoCompensacionAgentTools;
import com.uniride.agent.tools.ReportesAgentTools;
import com.uniride.agent.tools.RutaAgentTools;
import com.uniride.dto.requests.MetodoCompensacionRequest;
import com.uniride.dto.requests.RutaRequest;
import com.uniride.dto.responses.EstadisticasRespuesta;
import com.uniride.entities.Ruta;
import com.uniride.entities.Usuario;
import com.uniride.entities.Viaje;
import com.uniride.enums.Rol;
import com.uniride.enums.EstadoRuta;
import com.uniride.enums.EstadoViaje;
import com.uniride.enums.TipoCompensacion;
import com.uniride.exceptions.GlobalExceptionHandler;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.mappers.UsuarioMapper;
import com.uniride.mappers.BusquedaMapper;
import com.uniride.mappers.RutaMapper;
import com.uniride.repositories.MetodoCompensacionRepository;
import com.uniride.repositories.RutaRepository;
import com.uniride.repositories.SolicitudRepository;
import com.uniride.repositories.UsuarioRepository;
import com.uniride.repositories.ViajeRepository;
import com.uniride.security.*;
import com.uniride.services.EstadisticasService;
import com.uniride.services.ContribucionService;
import com.uniride.services.MensajeService;
import com.uniride.services.PerfilRolService;
import com.uniride.services.SolicitudService;
import com.uniride.services.UsuarioService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

/** Real security chain and JWT parser; all business and model interactions are simulated. */
@SpringJUnitConfig(AgentHttpSecurityTest.Configuration.class)
@WebAppConfiguration
@TestPropertySource(properties = {
        "uniride.jwt.secret=" + AgentHttpSecurityTest.TEST_SECRET,
        "uniride.jwt.expiracion-ms=3600000"
})
class AgentHttpSecurityTest {
    static final String TEST_SECRET = "dW5pcmlkZS10ZXN0LXNlY3JldC1kZS0zMi1ieXRlcyE=";
    static final Instant NOW = Instant.parse("2026-11-21T15:00:00Z");
    private static final String CONTRIBUCION = "¿Cuánto tendría que aportar por un viaje de Surco a la UPC?";
    @Autowired WebApplicationContext context;
    @Autowired UsuarioRepository usuarios;
    @Autowired AgentService agent;
    @Autowired TranscripcionService transcripcion;
    @Autowired ConfirmationService confirmations;
    @Autowired JwtService jwt;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        reset(usuarios, agent, transcripcion, confirmations);
        when(usuarios.findByCorreoInstitucional("ana@upc.edu.pe")).thenReturn(Optional.of(
                Usuario.builder().id(7L).correoInstitucional("ana@upc.edu.pe").contrasenaHash("fixture")
                        .rolPrincipal(Rol.CONDUCTOR).cuentaVerificada(true).build()));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void withoutJwtDoesNotReachAgent() throws Exception {
        mvc.perform(post("/agent/chat").contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(Map.of("message", CONTRIBUCION))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(agent);
    }

    @Test
    void invalidJwtDoesNotReachAgent() throws Exception {
        mvc.perform(post("/agent/chat").header("Authorization", "Bearer token-invalido")
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Mis rutas\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(agent);
    }

    @Test
    void expiredSignedJwtDoesNotReachAgent() throws Exception {
        String expired = Jwts.builder().subject("ana@upc.edu.pe")
                .issuedAt(Date.from(NOW.minusSeconds(7200))).expiration(Date.from(NOW.minusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(TEST_SECRET))).compact();
        mvc.perform(post("/agent/chat").header("Authorization", "Bearer " + expired)
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Mis rutas\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(agent);
    }

    @Test
    void validJwtKeepsExistingChatContract() throws Exception {
        when(agent.chat(eq("Mis rutas"), isNull(UUID.class))).thenReturn(
                new ChatResponse("No tienes rutas.", List.of(), List.of(), List.of(), null));
        mvc.perform(post("/agent/chat").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Mis rutas\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("No tienes rutas."))
                .andExpect(jsonPath("$.viajes").isArray())
                .andExpect(jsonPath("$.rutas").isArray())
                .andExpect(jsonPath("$.metodosCompensacion").isArray())
                .andExpect(jsonPath("$.operacion").isEmpty())
                .andExpect(jsonPath("$.aclaracion").doesNotExist());
        verify(agent).chat("Mis rutas", (UUID) null);
    }

    @Test
    void routeFormSurvivesTextToVoiceAndTextSubmissionOnlyPrepares() throws Exception {
        ChatClient chatClient = routeAgent();
        JsonNode first = json(sendChat("Crea una ruta con origen Surco", null, bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aclaracion.version").value(1))
                .andExpect(jsonPath("$.aclaracion.contexto").isString())
                .andExpect(jsonPath("$.aclaracion.operacion").value("crearRuta"))
                .andExpect(jsonPath("$.aclaracion.campos[*].id", org.hamcrest.Matchers.containsInAnyOrder("referencias", "capacidad")))
                .andExpect(jsonPath("$.operacion.confirmacion").isEmpty()));
        String conversationId = first.path("conversationId").asText();
        String firstForm = first.path("aclaracion").path("id").asText();
        UUID.fromString(firstForm);
        verifyNoInteractions(confirmations);

        when(transcripcion.transcribir(any())).thenReturn("Puntos de referencia: Parque central");
        JsonNode voiced = json(mvc.perform(multipart("/agent/voz").file(audio())
                        .param("conversationId", conversationId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transcripcion").value("Puntos de referencia: Parque central"))
                .andExpect(jsonPath("$.textoHablado").isNotEmpty())
                .andExpect(jsonPath("$.respuesta.conversationId").value(conversationId))
                .andExpect(jsonPath("$.respuesta.aclaracion.campos[0].id").value("capacidad"))
                .andExpect(jsonPath("$.respuesta.aclaracion.campos[0].tipo").value("number"))
                .andExpect(jsonPath("$.respuesta.aclaracion.campos[0].minimo").value(1))
                .andExpect(jsonPath("$.respuesta.aclaracion.campos.length()").value(1)));
        String form = voiced.path("respuesta").path("aclaracion").path("id").asText();
        sendChat("Datos de aclaración " + firstForm + ": {\"capacidad\":3}", conversationId, bearer())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.campos.codigo").value("CLARIFICATION_EXPIRED"));
        sendChat("Datos de aclaración " + form + ": {\"capacidad\":3,\"usuarioId\":99}", conversationId, bearer())
                .andExpect(status().isBadRequest());
        sendChat("Datos de aclaración " + form + ": {\"capacidad\":3,\"capacidad\":4}", conversationId, bearer())
                .andExpect(status().isBadRequest());
        verifyNoInteractions(confirmations);

        UUID proposalId = UUID.randomUUID();
        var request = new RutaRequest("Surco", 3, "Parque central", null, null);
        when(confirmations.prepararRuta(request)).thenReturn(new OperacionResumen(
                EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Revisa la ruta", confirmation(proposalId, EstadoConfirmacion.PENDIENTE)));
        String submission = "Datos de aclaración " + form + ": {\"capacidad\":3}";
        sendChat(submission, conversationId, bearer()).andExpect(status().isOk())
                .andExpect(jsonPath("$.operacion.estado").value("CONFIRMACION_REQUERIDA"))
                .andExpect(jsonPath("$.operacion.confirmacion.id").value(proposalId.toString()))
                .andExpect(jsonPath("$.resolucionConfirmacion").doesNotExist())
                .andExpect(jsonPath("$.aclaracion").doesNotExist());
        sendChat(submission, conversationId, bearer()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.campos.codigo").value("CLARIFICATION_EXPIRED"));
        verify(confirmations).prepararRuta(request);
        verifyNoMoreInteractions(confirmations);
        verifyNoInteractions(chatClient);
    }

    @Test
    void staleFormCannotCrossUsersConversationsOrRoles() throws Exception {
        ChatClient chatClient = routeAgent();
        JsonNode first = json(sendChat("Crea una ruta más", null, bearer()).andExpect(status().isOk())
                .andExpect(jsonPath("$.aclaracion.campos.length()").value(3)));
        String conversationId = first.path("conversationId").asText();
        String form = first.path("aclaracion").path("id").asText();
        String submission = "Datos de aclaración " + form + ": {\"origen\":\"Surco\",\"referencias\":\"Parque\",\"capacidad\":3}";

        when(usuarios.findByCorreoInstitucional("luis@upc.edu.pe")).thenReturn(Optional.of(
                Usuario.builder().id(8L).correoInstitucional("luis@upc.edu.pe").contrasenaHash("fixture")
                        .rolPrincipal(Rol.CONDUCTOR).cuentaVerificada(true).build()));
        sendChat(submission, conversationId, "Bearer " + jwt.generarToken("luis@upc.edu.pe"))
                .andExpect(status().isNotFound());
        sendChat(submission, null, bearer()).andExpect(status().is4xxClientError());

        when(usuarios.findByCorreoInstitucional("ana@upc.edu.pe")).thenReturn(Optional.of(
                Usuario.builder().id(7L).correoInstitucional("ana@upc.edu.pe").contrasenaHash("fixture")
                        .rolPrincipal(Rol.PASAJERO).cuentaVerificada(true).build()));
        sendChat(submission, conversationId, bearer()).andExpect(status().isConflict());
        verifyNoInteractions(confirmations, chatClient);
    }

    private ChatClient routeAgent() {
        var chatClient = mock(ChatClient.class);
        var users = new UsuarioService(usuarios, mock(UsuarioMapper.class), mock(PerfilRolService.class));
        var registry = new AgentToolRegistry(new ObjectMapper(),
                ToolCallbacks.from(new RutaAgentTools(mock(AgentQueryService.class), confirmations, users)));
        var clock = Clock.fixed(NOW, ZoneId.of("America/Lima"));
        var service = new AgentService(chatClient, users, clock, registry, new AgentConversationStore(clock), confirmations);
        when(agent.chat(anyString(), nullable(UUID.class)))
                .thenAnswer(call -> service.chat(call.getArgument(0, String.class), call.getArgument(1, UUID.class)));
        when(agent.chatVoz(anyString(), nullable(UUID.class)))
                .thenAnswer(call -> service.chatVoz(call.getArgument(0, String.class), call.getArgument(1, UUID.class)));
        return chatClient;
    }

    private ResultActions sendChat(String message, String conversationId, String authorization) throws Exception {
        var payload = new LinkedHashMap<String, String>();
        payload.put("message", message);
        if (conversationId != null) payload.put("conversationId", conversationId);
        return mvc.perform(post("/agent/chat").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content(new ObjectMapper().writeValueAsString(payload)));
    }

    private static JsonNode json(ResultActions result) {
        return new ObjectMapper().readTree(result.andReturn().getResponse().getContentAsByteArray());
    }

    @Test
    void passesConversationUuidAsDataWithoutChangingJwtIdentity() throws Exception {
        UUID conversation = UUID.randomUUID();
        when(agent.chat(eq("Continúa"), eq(conversation))).thenReturn(
                new ChatResponse("¿Qué fecha deseas?", List.of(), List.of(), List.of(), null));
        mvc.perform(post("/agent/chat").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Continúa\",\"conversationId\":\"" + conversation + "\"}"))
                .andExpect(status().isOk());
        verify(agent).chat("Continúa", conversation);
        verify(usuarios).findByCorreoInstitucional("ana@upc.edu.pe");
    }

    @Test
    void voiceWithoutJwtDoesNotReachTranscription() throws Exception {
        mvc.perform(multipart("/agent/voz").file(audio()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(transcripcion, agent);
    }

    @Test
    void voiceReturnsTranscriptSpokenTextAndChatEnvelope() throws Exception {
        UUID conversation = UUID.randomUUID();
        var ruta = new RutaResumen(7L, "Surco", "UPC", null, null, 4, EstadoRuta.ACTIVA);
        when(transcripcion.transcribir(any())).thenReturn("¿Cuáles son mis rutas?");
        when(agent.chatVoz("¿Cuáles son mis rutas?", conversation)).thenReturn(
                new ChatResponse("Tienes una ruta.", List.of(), List.of(ruta), List.of(), null,
                        List.of(), null, conversation));

        mvc.perform(multipart("/agent/voz").file(audio())
                        .param("conversationId", conversation.toString())
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transcripcion").value("¿Cuáles son mis rutas?"))
                .andExpect(jsonPath("$.textoHablado",
                        org.hamcrest.Matchers.containsString("Surco a UPC")))
                .andExpect(jsonPath("$.respuesta.message").value("Tienes una ruta."))
                .andExpect(jsonPath("$.respuesta.rutas.length()").value(1))
                .andExpect(jsonPath("$.respuesta.conversationId").value(conversation.toString()));

        verify(transcripcion).transcribir(any());
        verify(agent).chatVoz("¿Cuáles son mis rutas?", conversation);
    }

    @Test
    void passengerContinuesContributionFromTextToVoiceWithSimulatedTranscriptionAndNoChatProvider() throws Exception {
        when(usuarios.findByCorreoInstitucional("ana@upc.edu.pe")).thenReturn(Optional.of(
                Usuario.builder().id(7L).correoInstitucional("ana@upc.edu.pe").contrasenaHash("fixture")
                        .rolPrincipal(Rol.PASAJERO).cuentaVerificada(true).build()));
        var trips = mock(ViajeRepository.class);
        var queries = mock(AgentQueryService.class);
        var usuarioService = new UsuarioService(usuarios, mock(UsuarioMapper.class), mock(PerfilRolService.class));
        var chatClient = mock(ChatClient.class);
        var service = contributionAgent(chatClient, usuarioService, queries, trips);
        when(agent.chat(anyString(), nullable(UUID.class)))
                .thenAnswer(call -> service.chat(call.getArgument(0, String.class), call.getArgument(1, UUID.class)));
        when(agent.chatVoz(anyString(), nullable(UUID.class)))
                .thenAnswer(call -> service.chatVoz(call.getArgument(0, String.class), call.getArgument(1, UUID.class)));
        var date = LocalDate.of(2026, 11, 25);
        var time = LocalTime.of(8, 0);
        var trip = Viaje.builder().id(90L).fecha(date).hora(time).dia("Miercoles")
                .ruta(Ruta.builder().origen("Surco").destino("UPC").build()).estado(EstadoViaje.PROGRAMADO).build();
        var option = new ContribucionResultado.Opcion("Surco", "UPC", "Miercoles", date, time, "Ana Prueba");
        when(queries.identificarViajes(any(ContribucionFiltros.class)))
                .thenReturn(new PageImpl<>(List.of(new AgentQueryService.ViajeContribucion(90L, option))));
        when(trips.findById(90L)).thenReturn(Optional.of(trip));
        var mapper = new ObjectMapper();
        String body = mvc.perform(post("/agent/chat").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("message", CONTRIBUCION))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].tipo").value(NOMBRE))
                .andExpect(jsonPath("$.resultados[0].datos.estado").value("FALTAN_PASAJEROS"))
                .andExpect(jsonPath("$.aclaracion.operacion").value(NOMBRE))
                .andExpect(jsonPath("$.aclaracion.campos[*].id", org.hamcrest.Matchers.contains("pasajeros")))
                .andExpect(jsonPath("$.operacion").isEmpty())
                .andExpect(jsonPath("$.resolucionConfirmacion").doesNotExist())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String conversation = mapper.readTree(body).path("conversationId").asText();
        when(transcripcion.transcribir(any())).thenReturn("3 pasajeros");

        mvc.perform(multipart("/agent/voz").file(audio()).param("conversationId", conversation)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transcripcion").value("3 pasajeros"))
                .andExpect(jsonPath("$.textoHablado", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("4 soles"),
                        org.hamcrest.Matchers.containsString("3 pasajeros"),
                        org.hamcrest.Matchers.containsString("depende del acuerdo"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("999")))))
                .andExpect(jsonPath("$.respuesta.conversationId").value(conversation))
                .andExpect(jsonPath("$.respuesta.operacion").isEmpty())
                .andExpect(jsonPath("$.respuesta.resolucionConfirmacion").doesNotExist())
                .andExpect(jsonPath("$.respuesta.aclaracion").doesNotExist())
                .andExpect(jsonPath("$.respuesta.resultados[0].tipo").value(NOMBRE))
                .andExpect(jsonPath("$.respuesta.resultados[0].datos.sugerencia.costoTotalEstimado").value(12.0))
                .andExpect(jsonPath("$.respuesta.resultados[0].datos.sugerencia.aporteEstimado").value(4.0))
                .andExpect(jsonPath("$.respuesta.resultados[0].datos.sugerencia.pasajeros").value(3));
        verifyNoInteractions(confirmations, chatClient);
    }

    @Test
    void contributionRequiresPassengerRoleWithTheRealJwtIdentity() throws Exception {
        var trips = mock(ViajeRepository.class);
        var usuarioService = new UsuarioService(usuarios, mock(UsuarioMapper.class), mock(PerfilRolService.class));
        var clock = Clock.fixed(NOW, ZoneId.of("America/Lima"));
        var queries = new AgentQueryService(mock(RutaRepository.class), trips, mock(MetodoCompensacionRepository.class),
                mock(SolicitudRepository.class), mock(SolicitudService.class), usuarioService,
                mock(RutaMapper.class), mock(BusquedaMapper.class), clock);
        var chatClient = mock(ChatClient.class);
        var service = contributionAgent(chatClient, usuarioService, queries, trips);
        when(agent.chat(anyString(), nullable(UUID.class)))
                .thenAnswer(call -> service.chat(call.getArgument(0, String.class), call.getArgument(1, UUID.class)));

        mvc.perform(post("/agent/chat").header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("message", CONTRIBUCION))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("PASAJERO")))
                .andExpect(jsonPath("$.resultados").doesNotExist());
        verifyNoInteractions(trips, confirmations, chatClient);
    }

    private AgentService contributionAgent(ChatClient chatClient, UsuarioService usuarioService,
                                           AgentQueryService queries, ViajeRepository trips) {
        var registry = new AgentToolRegistry(new ObjectMapper(),
                ToolCallbacks.from(new ContribucionAgentTools(queries, new ContribucionService(trips))));
        var clock = Clock.fixed(NOW, ZoneId.of("America/Lima"));
        return new AgentService(chatClient, usuarioService, clock,
                registry, new AgentConversationStore(clock), confirmations);
    }

    @Test
    void voiceReturnsControlledTranscriptionError() throws Exception {
        when(transcripcion.transcribir(any())).thenThrow(new AgentProviderException(
                HttpStatus.BAD_REQUEST, "VOICE_NOT_UNDERSTOOD", "No se entendió el audio."));

        mvc.perform(multipart("/agent/voz").file(audio()).header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.codigo").value("VOICE_NOT_UNDERSTOOD"));
        verifyNoInteractions(agent);
    }

    private static MockMultipartFile audio() {
        return new MockMultipartFile("audio", "voz.webm", "audio/webm", new byte[2048]);
    }

    @Test
    void reportUsesExistingChatEnvelopeAndKeepsConversationUuid() throws Exception {
        UUID conversation = UUID.randomUUID();
        String question = "¿Cuántos viajes he realizado?";
        var resumen = new EstadisticasRespuesta(8, 4, 2, 1);
        var result = new ResultadoAgente("consultarMisEstadisticas", new ObjectMapper().valueToTree(resumen));
        when(agent.chat(eq(question), eq(conversation))).thenReturn(
                new ChatResponse("Has completado 12 viajes.", List.of(), List.of(), List.of(),
                        null, List.of(result), null, conversation));

        mvc.perform(post("/agent/chat").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"message":"%s","conversationId":"%s"}
                        """.formatted(question, conversation)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Has completado 12 viajes."))
                .andExpect(jsonPath("$.viajes").isEmpty())
                .andExpect(jsonPath("$.rutas").isEmpty())
                .andExpect(jsonPath("$.metodosCompensacion").isEmpty())
                .andExpect(jsonPath("$.operacion").isEmpty())
                .andExpect(jsonPath("$.advertencia").isEmpty())
                .andExpect(jsonPath("$.resolucionConfirmacion").doesNotExist())
                .andExpect(jsonPath("$.conversationId").value(conversation.toString()))
                .andExpect(jsonPath("$.resultados.length()").value(1))
                .andExpect(jsonPath("$.resultados[0].tipo").value("consultarMisEstadisticas"))
                .andExpect(jsonPath("$.resultados[0].datos.viajesComoConductor").value(8))
                .andExpect(jsonPath("$.resultados[0].datos.viajesComoPasajero").value(4))
                .andExpect(jsonPath("$.resultados[0].datos.viajesCancelados").value(2))
                .andExpect(jsonPath("$.resultados[0].datos.penalidadesAcumuladas").value(1));
        verifyNoInteractions(confirmations);
    }

    @Test
    void reportDenialReturnsForbiddenForPassengerJwtWithoutCallingTheProvider() throws Exception {
        when(usuarios.findByCorreoInstitucional("ana@upc.edu.pe")).thenReturn(Optional.of(
                Usuario.builder().id(7L).correoInstitucional("ana@upc.edu.pe").contrasenaHash("fixture")
                        .rolPrincipal(Rol.PASAJERO).cuentaVerificada(true).build()));
        var estadisticas = mock(EstadisticasService.class);
        var usuarioService = new UsuarioService(usuarios, mock(UsuarioMapper.class), mock(PerfilRolService.class));
        var reportes = new ReportesAgentTools(estadisticas, usuarioService);
        var chatClient = mock(ChatClient.class);
        var registry = new AgentToolRegistry(new ObjectMapper(), ToolCallbacks.from(reportes));
        var clock = Clock.fixed(NOW, ZoneId.of("America/Lima"));
        var service = new AgentService(chatClient, usuarioService, clock,
                registry, new AgentConversationStore(clock), confirmations);
        when(agent.chat(eq("Ranking de mis rutas"), isNull(UUID.class)))
                .thenAnswer(call -> service.chat(call.getArgument(0, String.class), (UUID) null));

        mvc.perform(post("/agent/chat").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Ranking de mis rutas\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsStringIgnoringCase("conductor")))
                .andExpect(jsonPath("$.resultados").doesNotExist());
        verifyNoInteractions(estadisticas, confirmations, chatClient);
    }

    @Test
    void textualConfirmationRequiresJwtAndSerializesThePersistedReceipt() throws Exception {
        var mapper = new ObjectMapper();
        var userService = new UsuarioService(usuarios, mock(UsuarioMapper.class), mock(PerfilRolService.class));
        var tools = new MetodoCompensacionAgentTools(mock(AgentQueryService.class), confirmations);
        var registry = new AgentToolRegistry(mapper, ToolCallbacks.from(tools));
        var clock = Clock.fixed(NOW, ZoneId.of("America/Lima"));
        var chatClient = mock(ChatClient.class);
        var service = new AgentService(chatClient, userService, clock, registry,
                new AgentConversationStore(clock), confirmations);
        doAnswer(call -> service.chat(call.getArgument(0, String.class), call.getArgument(1, UUID.class)))
                .when(agent).chat(anyString(), nullable(UUID.class));

        UUID id = UUID.randomUUID();
        var parameters = new MetodoCompensacionRequest(TipoCompensacion.FAVOR, "Apuntes de clase", true);
        var pending = new ConfirmacionResumen(id, TipoOperacion.REGISTRAR_METODO, EstadoConfirmacion.PENDIENTE,
                "Registrar método FAVOR «Apuntes de clase».", mapper.valueToTree(parameters), NOW.plusSeconds(600), null);
        var result = new OperacionResumen(EstadoOperacion.EXITO, 61L, "Método FAVOR registrado.");
        var executed = new ConfirmacionResumen(id, pending.tipo(), EstadoConfirmacion.EJECUTADA, pending.resumen(),
                pending.parametros(), pending.expira(), result);
        when(confirmations.prepararMetodo(parameters))
                .thenReturn(new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null, pending.resumen(), pending));
        when(confirmations.resolverDesdeChat(id, true))
                .thenReturn(new ResolucionConfirmacion(ResolucionConfirmacion.Estado.EJECUTADA, executed));

        String preparation = mvc.perform(post("/agent/chat").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Registra un método FAVOR con descripción Apuntes de clase\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operacion.confirmacion.id").value(id.toString()))
                .andExpect(jsonPath("$.resolucionConfirmacion").doesNotExist())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String conversation = mapper.readTree(preparation).path("conversationId").asText();
        String answer = "{\"message\":\"sí\",\"conversationId\":\"" + conversation + "\"}";

        mvc.perform(post("/agent/chat").contentType(MediaType.APPLICATION_JSON).content(answer))
                .andExpect(status().isForbidden());
        verify(confirmations, never()).resolverDesdeChat(any(UUID.class), anyBoolean());

        mvc.perform(post("/agent/chat").header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content(answer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversation))
                .andExpect(jsonPath("$.operacion.estado").value("EXITO"))
                .andExpect(jsonPath("$.operacion.id").value(61))
                .andExpect(jsonPath("$.resolucionConfirmacion.estado").value("EJECUTADA"))
                .andExpect(jsonPath("$.resolucionConfirmacion.confirmacion.id").value(id.toString()))
                .andExpect(jsonPath("$.resolucionConfirmacion.confirmacion.estado").value("EJECUTADA"))
                .andExpect(jsonPath("$.resolucionConfirmacion.confirmacion.resultado.detalle").value(result.detalle()));
        verify(confirmations).prepararMetodo(parameters);
        verify(confirmations).resolverDesdeChat(id, true);
        verifyNoMoreInteractions(confirmations);
        verifyNoInteractions(chatClient);
    }

    @Test
    void oversizedBlankAndMalformedRequestsAreRejectedBeforeCallingModel() throws Exception {
        for (String body : List.of("{\"message\":\"" + "a".repeat(1001) + "\"}",
                "{\"message\":\"   \"}", "{\"message\":", "{\"message\":\"Hola\",\"conversationId\":\"wrong\"}")) {
            mvc.perform(post("/agent/chat").header("Authorization", bearer())
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(agent);
    }

    @ParameterizedTest
    @CsvSource({"502,PROVIDER_REJECTED", "503,PROVIDER_UNAVAILABLE", "429,PROVIDER_RATE_LIMIT", "504,PROVIDER_TIMEOUT"})
    void providerErrorsUseDocumentedHttpStatusAndSafeBody(int status, String code) throws Exception {
        when(agent.chat(anyString(), nullable(UUID.class))).thenThrow(
                new AgentProviderException(HttpStatus.valueOf(status), code, "El proveedor no completó la respuesta."));
        mvc.perform(post("/agent/chat").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Mis rutas\"}"))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.mensaje").value("El proveedor no completó la respuesta."))
                .andExpect(jsonPath("$.campos.codigo").value(code));
        verify(agent, times(1)).chat("Mis rutas", (UUID) null);
    }

    private String bearer() { return "Bearer " + jwt.generarToken("ana@upc.edu.pe"); }

    @Test
    void everyConfirmationEndpointRequiresJwt() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/agent/confirmaciones/" + id)).andExpect(status().isForbidden());
        mvc.perform(post("/agent/confirmaciones/" + id + "/confirmar")).andExpect(status().isForbidden());
        mvc.perform(post("/agent/confirmaciones/" + id + "/cancelar")).andExpect(status().isForbidden());
        verifyNoInteractions(confirmations);
    }

    @Test
    void confirmationAndCancellationRejectAlteredParameters() throws Exception {
        UUID id = UUID.randomUUID();
        for (String action : List.of("confirmar", "cancelar")) {
            mvc.perform(post("/agent/confirmaciones/" + id + "/" + action)
                    .header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"origen\":\"otro destino\"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post("/agent/confirmaciones/" + id + "/" + action)
                    .header("Authorization", bearer()).param("usuarioId", "999"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(confirmations);
    }

    @Test
    void explicitConfirmationAcceptsOnlyThePendingUuid() throws Exception {
        UUID id = UUID.randomUUID();
        when(confirmations.confirmar(id)).thenReturn(confirmation(id, EstadoConfirmacion.EJECUTADA));
        mvc.perform(post("/agent/confirmaciones/" + id + "/confirmar").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.estado").value("EJECUTADA"));
        verify(confirmations, times(1)).confirmar(id);
    }

    @Test
    void pendingAndCancelledStatusRemainStructuredForFutureButtons() throws Exception {
        UUID id = UUID.randomUUID();
        when(confirmations.obtener(id)).thenReturn(confirmation(id, EstadoConfirmacion.PENDIENTE));
        when(confirmations.cancelar(id)).thenReturn(confirmation(id, EstadoConfirmacion.CANCELADA));
        mvc.perform(get("/agent/confirmaciones/" + id).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("PENDIENTE"));
        mvc.perform(post("/agent/confirmaciones/" + id + "/cancelar").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CANCELADA"));
    }

    @Test
    void expiredConfirmationReturnsGoneWithStructuredStatus() throws Exception {
        UUID id = UUID.randomUUID();
        when(confirmations.confirmar(id)).thenReturn(confirmation(id, EstadoConfirmacion.EXPIRADA));
        mvc.perform(post("/agent/confirmaciones/" + id + "/confirmar").header("Authorization", bearer()))
                .andExpect(status().isGone()).andExpect(jsonPath("$.estado").value("EXPIRADA"));
    }

    @Test
    void foreignConfirmationIsNotDisclosedAndRoleChangesAreForbidden() throws Exception {
        UUID id = UUID.randomUUID();
        when(confirmations.obtener(id)).thenThrow(new ResourceNotFoundException("Confirmación no encontrada"));
        when(confirmations.confirmar(id)).thenThrow(new NoPermitidoException("El rol actual no permite la operación"));
        mvc.perform(get("/agent/confirmaciones/" + id).header("Authorization", bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/agent/confirmaciones/" + id + "/confirmar").header("Authorization", bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancelledOrChangedOperationUsesConflictInsteadOf500() throws Exception {
        UUID id = UUID.randomUUID();
        when(confirmations.confirmar(id)).thenThrow(new BusinessException("La operación fue cancelada o sus datos cambiaron"));
        mvc.perform(post("/agent/confirmaciones/" + id + "/confirmar").header("Authorization", bearer()))
                .andExpect(status().isConflict());
    }

    @Test
    void uncertainCommitReturns503AndReconcilesUsingSameUuidWithoutReexecuting() throws Exception {
        UUID id = UUID.randomUUID();
        when(confirmations.confirmar(id)).thenThrow(
                new org.springframework.transaction.TransactionSystemException("detalleinterno"));
        when(confirmations.obtener(id)).thenReturn(confirmation(id, EstadoConfirmacion.EJECUTADA));

        mvc.perform(post("/agent/confirmaciones/" + id + "/confirmar").header("Authorization", bearer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.campos.codigo").value("CONFIRMATION_RESULT_UNCERTAIN"))
                .andExpect(jsonPath("$.mensaje").value(
                        org.hamcrest.Matchers.containsString("mismo identificador")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("detalleinterno"))));
        mvc.perform(get("/agent/confirmaciones/" + id).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("EJECUTADA"));

        verify(confirmations, times(1)).confirmar(id);
        verify(confirmations, times(1)).obtener(id);
        verifyNoMoreInteractions(confirmations);
    }

    private ConfirmacionResumen confirmation(UUID id, EstadoConfirmacion state) {
        return new ConfirmacionResumen(id, TipoOperacion.CREAR_RUTA, state,
                "Publicar ruta de Surco a UPC", null, NOW.plusSeconds(600), null);
    }

    @org.springframework.context.annotation.Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class, UsuarioDetailsService.class,
            LimiteAutenticacion.class, AgentController.class, AgentVozController.class, ConfirmationController.class,
            GlobalExceptionHandler.class, MensajeService.class})
    static class Configuration {
        @Bean UsuarioRepository usuarios() { return mock(UsuarioRepository.class); }
        @Bean AgentService agent() { return mock(AgentService.class); }
        @Bean TranscripcionService transcripcion() { return mock(TranscripcionService.class); }
        @Bean ConfirmationService confirmations() { return mock(ConfirmationService.class); }
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneId.of("America/Lima")); }
    }
}
