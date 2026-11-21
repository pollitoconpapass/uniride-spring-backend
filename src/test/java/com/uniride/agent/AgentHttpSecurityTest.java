package com.uniride.agent;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.uniride.agent.dto.ChatResponse;
import com.uniride.agent.dto.ConfirmacionResumen;
import com.uniride.agent.confirmation.*;
import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import com.uniride.exceptions.GlobalExceptionHandler;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.NoPermitidoException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.repositories.UsuarioRepository;
import com.uniride.security.*;
import com.uniride.services.MensajeService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

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
    @Autowired WebApplicationContext context;
    @Autowired UsuarioRepository usuarios;
    @Autowired AgentService agent;
    @Autowired ConfirmationService confirmations;
    @Autowired JwtService jwt;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        reset(usuarios, agent, confirmations);
        when(usuarios.findByCorreoInstitucional("ana@upc.edu.pe")).thenReturn(Optional.of(
                Usuario.builder().id(7L).correoInstitucional("ana@upc.edu.pe").contrasenaHash("fixture")
                        .rolPrincipal(Rol.CONDUCTOR).cuentaVerificada(true).build()));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void withoutJwtDoesNotReachAgent() throws Exception {
        mvc.perform(post("/agent/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Mis rutas\"}"))
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
                .andExpect(jsonPath("$.operacion").isEmpty());
        verify(agent).chat("Mis rutas", (UUID) null);
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
            LimiteAutenticacion.class, AgentController.class, ConfirmationController.class,
            GlobalExceptionHandler.class, MensajeService.class})
    static class Configuration {
        @Bean UsuarioRepository usuarios() { return mock(UsuarioRepository.class); }
        @Bean AgentService agent() { return mock(AgentService.class); }
        @Bean ConfirmationService confirmations() { return mock(ConfirmationService.class); }
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneId.of("America/Lima")); }
    }
}
