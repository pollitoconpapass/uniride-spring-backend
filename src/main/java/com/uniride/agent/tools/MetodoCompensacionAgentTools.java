package com.uniride.agent.tools;

import com.uniride.agent.dto.EstadoOperacion;
import com.uniride.agent.dto.MetodoCompensacionResumen;
import com.uniride.agent.dto.OperacionResumen;
import com.uniride.dto.requests.MetodoCompensacionRequest;
import com.uniride.dto.responses.MetodoCompensacionRespuesta;
import com.uniride.enums.TipoCompensacion;
import com.uniride.exceptions.BusinessException;
import com.uniride.exceptions.CamposInvalidosException;
import com.uniride.exceptions.ResourceNotFoundException;
import com.uniride.services.MetodoCompensacionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MetodoCompensacionAgentTools {

    private final MetodoCompensacionService metodoCompensacionService;

    @Tool(description = """
            Consulta los métodos de compensación del usuario autenticado.
            Sirve tanto para pasajeros como para conductores.
            """)
    public List<MetodoCompensacionResumen> consultarMisMetodosCompensacion() {
        return metodoCompensacionService.listar().stream()
                .map(this::toResumen)
                .toList();
    }

    @Tool(description = """
            Registra un método de compensación para el usuario autenticado.
            Sirve tanto para pasajeros como para conductores.
            """)
    public OperacionResumen registrarMetodoCompensacion(
            @ToolParam(description = "Tipo: DINERO, GASOLINA o FAVOR") TipoCompensacion tipo,
            @ToolParam(description = "Descripción del método, por ejemplo Yape 999888777") String descripcion) {
        if (tipo == null || descripcion == null || descripcion.isBlank()) {
            return new OperacionResumen(EstadoOperacion.DATOS_FALTANTES, null,
                    "Indica el tipo de compensación y una descripción.");
        }

        try {
            MetodoCompensacionRespuesta metodo = metodoCompensacionService.registrar(
                    new MetodoCompensacionRequest(tipo, descripcion, true));
            return new OperacionResumen(EstadoOperacion.EXITO, metodo.id(),
                    "Método " + metodo.tipo() + ": " + metodo.descripcion() + " registrado.");
        } catch (BusinessException | CamposInvalidosException | ResourceNotFoundException e) {
            return new OperacionResumen(EstadoOperacion.ERROR, null, e.getMessage());
        }
    }

    private MetodoCompensacionResumen toResumen(MetodoCompensacionRespuesta metodo) {
        return new MetodoCompensacionResumen(metodo.id(), metodo.tipo(),
                metodo.descripcion(), metodo.activo());
    }
}
