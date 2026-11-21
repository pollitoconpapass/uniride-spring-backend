package com.uniride.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.dto.*;
import com.uniride.agent.tools.*;
import com.uniride.entities.Usuario;
import com.uniride.enums.Rol;
import com.uniride.services.UsuarioService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.ObjectMapper;

class AgentToolRegistryTest {
    private final AgentQueryService queries = mock(AgentQueryService.class);
    private final ConfirmationService confirmations = mock(ConfirmationService.class);
    private final UsuarioService users = mock(UsuarioService.class);
    private final AgentToolRegistry registry = new AgentToolRegistry(new ObjectMapper(),
            new ViajeAgentTools(queries, confirmations, users),
            new RutaAgentTools(queries, confirmations, users),
            new MetodoCompensacionAgentTools(queries, confirmations));

    @Test void registersEveryExistingToolWithoutAnyConfirmationOrJsonTool() {
        assertThat(Arrays.stream(registry.newTurn().callbacks()).map(c -> c.getToolDefinition().name()))
                .containsExactlyInAnyOrder("buscarViajes", "consultarMisViajes", "consultarMisSolicitudes",
                        "consultarMisRutas", "consultarMisMetodosCompensacion", "crearRuta", "crearViaje",
                        "solicitarUnirse", "registrarMetodoCompensacion");
        verifyNoInteractions(confirmations, queries, users);
    }

    @Test void repeatedArgumentsInDifferentOrderReturnSameReceiptAndOnlyOnePreparationIsAllowed() {
        when(users.usuarioActual()).thenReturn(Usuario.builder().id(1L).rolPrincipal(Rol.CONDUCTOR).build());
        when(confirmations.prepararRuta(any())).thenReturn(new OperacionResumen(
                EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Revisa la ruta antes de confirmar."));
        var turn = registry.newTurn();
        Map<String, ToolCallback> callbacks = new HashMap<>();
        for (var callback : turn.callbacks()) callbacks.put(callback.getToolDefinition().name(), callback);
        String first = callbacks.get("crearRuta").call("{\"origen\":\"Surco\",\"capacidadMaxima\":4,\"puntosReferencia\":\"Parque\"}");
        String repeated = callbacks.get("crearRuta").call("{\"puntosReferencia\":\"Parque\",\"capacidadMaxima\":4,\"origen\":\"Surco\"}");
        assertThat(repeated).isEqualTo(first);
        assertThatThrownBy(() -> callbacks.get("registrarMetodoCompensacion")
                .call("{\"tipo\":\"FAVOR\",\"descripcion\":\"Apuntes\"}"))
                .isInstanceOf(AgentProviderException.class);
        verify(confirmations, times(1)).prepararRuta(any());
        verify(confirmations, never()).prepararMetodo(any());
        assertThat(turn.response("Operación ejecutada por el modelo.").message()).isEqualTo("Revisa la ruta antes de confirmar.");
        assertThat(turn.response("Ignorar").operacion().estado()).isEqualTo(EstadoOperacion.CONFIRMACION_REQUERIDA);
    }

    @Test void unsupportedModelSuccessClaimDoesNotBecomeBackendSuccess() {
        ChatResponse response = registry.newTurn().response("Tu viaje quedó programado para mañana a las 08:00.");
        assertThat(response.message()).contains("preparar una operación para tu confirmación");
        assertThat(response.message()).doesNotContain("programado", "08:00");
        assertThat(response.operacion()).isNull();
        assertThat(response.resultados()).isEmpty();
    }

    @Test void toolBudgetStopsAnUnboundedLoopWithoutRepeatingARead() {
        when(queries.metodos(null)).thenReturn(new PaginaAgente<>(List.of(), 0, 10, 0, false));
        var turn = registry.newTurn();
        ToolCallback callback = Arrays.stream(turn.callbacks())
                .filter(c -> c.getToolDefinition().name().equals("consultarMisMetodosCompensacion")).findFirst().orElseThrow();
        for (int i = 0; i < 8; i++) callback.call("{}");
        assertThatThrownBy(() -> callback.call("{}"))
                .isInstanceOfSatisfying(AgentProviderException.class, e -> assertThat(e.code()).isEqualTo("TOOL_LIMIT"));
        verify(queries, times(1)).metodos(null);
    }
}
