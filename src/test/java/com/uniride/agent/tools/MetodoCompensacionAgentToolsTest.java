package com.uniride.agent.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.uniride.agent.AgentQueryService;
import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.dto.*;
import com.uniride.dto.requests.MetodoCompensacionRequest;
import com.uniride.enums.TipoCompensacion;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetodoCompensacionAgentToolsTest {
    private final AgentQueryService consultas = mock(AgentQueryService.class);
    private final ConfirmationService confirmaciones = mock(ConfirmationService.class);
    private final MetodoCompensacionAgentTools tools = new MetodoCompensacionAgentTools(consultas, confirmaciones);

    @Test void prepararNoRegistraMetodo() {
        var request = new MetodoCompensacionRequest(TipoCompensacion.GASOLINA, "Compartir combustible", true);
        var result = new OperacionResumen(EstadoOperacion.CONFIRMACION_REQUERIDA, null, "Confirma el método.");
        when(confirmaciones.prepararMetodo(request)).thenReturn(result);
        assertThat(tools.registrarMetodoCompensacion(request.tipo(), request.descripcion())).isSameAs(result);
        verify(confirmaciones).prepararMetodo(request);
        verifyNoInteractions(consultas);
    }

    @Test void datosFaltantesNoPreparanNada() {
        assertThat(tools.registrarMetodoCompensacion(null, "").estado()).isEqualTo(EstadoOperacion.DATOS_FALTANTES);
        verifyNoInteractions(consultas, confirmaciones);
    }

    @Test void consultarMetodosEsPaginado() {
        var page = new PaginaAgente<>(List.of(new MetodoCompensacionResumen(11L, TipoCompensacion.FAVOR, "Apuntes", true)),
                1, 10, 25, true);
        when(consultas.metodos(1)).thenReturn(page);
        assertThat(tools.consultarMisMetodosCompensacion(1)).isSameAs(page);
        assertThat(tools.consultarMisMetodosCompensacion(1).hayMas()).isTrue();
        verifyNoInteractions(confirmaciones);
    }
}
