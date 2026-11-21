package com.uniride.agent.tools;

import com.uniride.agent.AgentQueryService;
import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.dto.*;
import com.uniride.dto.requests.MetodoCompensacionRequest;
import com.uniride.enums.TipoCompensacion;
import com.uniride.exceptions.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MetodoCompensacionAgentTools {
    private final AgentQueryService consultas;
    private final ConfirmationService confirmaciones;

    @Tool(description = "Consulta métodos propios de compensación de ambos roles. Diez por página desde cero; hayMas indica otra página.")
    public PaginaAgente<MetodoCompensacionResumen> consultarMisMetodosCompensacion(
            @ToolParam(description = "Página desde cero", required = false) Integer pagina) {
        return consultas.metodos(pagina);
    }

    public List<MetodoCompensacionResumen> consultarMisMetodosCompensacion() {
        return consultarMisMetodosCompensacion(null).items();
    }

    @Tool(description = """
            Prepara un método propio de compensación para confirmación. NO registra todavía el método.
            Sirve para ambos roles. Solo el usuario confirma mediante el endpoint HTTP explícito.
            """)
    public OperacionResumen registrarMetodoCompensacion(
            @ToolParam(description = "Tipo DINERO, GASOLINA o FAVOR", required = false) TipoCompensacion tipo,
            @ToolParam(description = "Descripción del método", required = false) String descripcion) {
        if (tipo == null || descripcion == null || descripcion.isBlank())
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Indica el tipo de compensación y una descripción.");
        try {
            return confirmaciones.prepararMetodo(new MetodoCompensacionRequest(tipo, descripcion, true));
        } catch (BusinessException | CamposInvalidosException | ResourceNotFoundException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }
}