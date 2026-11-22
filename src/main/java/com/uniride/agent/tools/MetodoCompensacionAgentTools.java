package com.uniride.agent.tools;

import com.uniride.agent.AgentQueryService;
import com.uniride.agent.confirmation.ConfirmationService;
import com.uniride.agent.dto.*;
import com.uniride.dto.requests.MetodoCompensacionRequest;
import com.uniride.enums.TipoCompensacion;
import com.uniride.exceptions.*;
import java.util.List;
import java.util.function.Supplier;
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

    @Tool(description = """
            Prepara editar el tipo o la descripción de un método propio. Identifica el método por tipo o descripción actual.
            Proporciona solo los campos que el usuario pidió cambiar. Conserva el estado activo. Requiere confirmación HTTP.
            """)
    public OperacionResumen actualizarMetodoCompensacion(
            @ToolParam(description = "Tipo actual DINERO, GASOLINA o FAVOR", required = false) TipoCompensacion tipoActual,
            @ToolParam(description = "Descripción actual del método", required = false) String descripcionActual,
            @ToolParam(description = "Nuevo tipo DINERO, GASOLINA o FAVOR", required = false) TipoCompensacion nuevoTipo,
            @ToolParam(description = "Nueva descripción", required = false) String nuevaDescripcion) {
        return prepararGestion(() -> confirmaciones.prepararActualizacionMetodo(tipoActual, descripcionActual, nuevoTipo, nuevaDescripcion));
    }

    @Tool(description = """
            Prepara activar o desactivar un método propio por tipo o descripción actual. Requiere un estado deseado explícito.
            Conserva el tipo y la descripción. El usuario ejecuta el cambio mediante confirmación HTTP.
            """)
    public OperacionResumen cambiarEstadoMetodoCompensacion(
            @ToolParam(description = "Tipo actual DINERO, GASOLINA o FAVOR", required = false) TipoCompensacion tipoActual,
            @ToolParam(description = "Descripción actual del método", required = false) String descripcionActual,
            @ToolParam(description = "true para activar, false para desactivar", required = false) Boolean activo) {
        return prepararGestion(() -> confirmaciones.prepararEstadoMetodo(tipoActual, descripcionActual, activo));
    }

    @Tool(description = """
            Prepara eliminar un método propio por tipo o descripción actual si carece de solicitudes asociadas.
            Úsala solo ante un pedido explícito de eliminación. El usuario ejecuta la acción mediante confirmación HTTP.
            """)
    public OperacionResumen eliminarMetodoCompensacion(
            @ToolParam(description = "Tipo actual DINERO, GASOLINA o FAVOR", required = false) TipoCompensacion tipoActual,
            @ToolParam(description = "Descripción actual del método", required = false) String descripcionActual) {
        return prepararGestion(() -> confirmaciones.prepararEliminacionMetodo(tipoActual, descripcionActual));
    }

    private OperacionResumen prepararGestion(Supplier<OperacionResumen> preparar) {
        try {
            return preparar.get();
        } catch (ResourceNotFoundException e) {
            return new OperacionResumen(EstadoOperacion.NO_ENCONTRADO, null, "No encontré un método de compensación propio que coincida.");
        } catch (BusinessException | CamposInvalidosException | NoPermitidoException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }
}
